// Defines tasks for building the VDL tool and generating VDL files.

package io.v.vdl

import org.gradle.api.InvalidUserDataException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Exec
import org.gradle.language.jvm.tasks.ProcessResources

import java.util.jar.JarEntry
import java.util.jar.JarFile

class VdlPlugin implements Plugin<Project> {
    void apply(Project project) {
        project.extensions.create('vdl', VdlConfiguration)

        def extractVdlTask = project.task('extractVdl', type: Copy) {
            description = 'Extracts the platform-specific vdl tool from the JAR provided by the gradle-plugin.'
            from {
                getVdlToolJarFiles(project, project.buildscript.configurations.classpath)
            }
            into { new File(project.buildDir, 'vdltool') }
        }

        def clearVdlOutputTask = project.task('clearVdlOutput', type: Delete) {
            delete { project.vdl.outputPath }
            delete { project.vdl.transitiveVdlDir }
        }

        def generateVdlTask = project.task('generateVdl', type: Exec) {
            description = 'Runs the `vdl generate` to generate the Java source for all the .vdl files.'
        }

        def vdlTask = project.task('vdl') {
            group = 'Build'
            description('Generates Java sources for the .vdl files using the vdl tool.')
        }

        def prepareVdlTask = project.task('prepareVdl') {
            description = 'Prepares the generateVdl task.'

            doLast {
                List<String> vdlPaths = extractTransitiveVdlFilesAndGetInputPaths(project).asList()
                List<String> outDirs = getJavaOutDirs(project)

                generateVdlTask.environment(VDLROOT: getVdlRootPath(project))
                println "VDLROOT=${getVdlRootPath(project)}"
                generateVdlTask.environment(VDLPATH: vdlPaths.join(":"))
                println "VDLPATH=${vdlPaths.join(':')}"
                String vdlToolPath = 'build/vdltool/vdl-' + getOsName()
                if (project.vdl.vdlToolPath != "") {
                    vdlToolPath = project.vdl.vdlToolPath
                }
                List<String> commandLine = [vdlToolPath,
                                            'generate',
                                            '--lang=java',
                                            '--java-out-dir=' + outDirs.join(",")
                ]
                if (!project.vdl.packageTranslations.isEmpty()) {
                    commandLine.add('--java-out-pkg=' + project.vdl.packageTranslations.join(','))
                }
                commandLine.add('all')
                generateVdlTask.commandLine(commandLine)
            }
        }

        def removeVdlRootTask = project.task('removeVdlRoot', type: Delete) {
            onlyIf { !project.vdl.generateVdlRoot }
            delete { project.vdl.outputPath + '/io/v/v23/vdlroot/' }
        }

        extractVdlTask.dependsOn(prepareVdlTask)
        generateVdlTask.dependsOn(clearVdlOutputTask)
        generateVdlTask.dependsOn(extractVdlTask)
        removeVdlRootTask.dependsOn(generateVdlTask)
        vdlTask.dependsOn(removeVdlRootTask)

        if (project.vdl.formatGeneratedVdl) {
            def formatTask = project.task('formatGeneratedVdlFiles', type: FormatTask) {
                format project.fileTree({ project.vdl.outputPath }).include('**/*.java')
            }
            formatTask.dependsOn(removeVdlRootTask)
            vdlTask.dependsOn(formatTask)
        }
        if (project.plugins.hasPlugin('com.android.library')
                || project.plugins.hasPlugin('com.android.application')) {
            project.tasks.'preBuild'.dependsOn(vdlTask)
            project.android.sourceSets.main.java.srcDir({ project.vdl.outputPath })
        }

        if (project.plugins.hasPlugin('java')) {
            project.compileJava.dependsOn(vdlTask)
            project.sourceSets.main.java.srcDir({ project.vdl.outputPath })
            project.sourceSets.main.java.srcDir({ project.vdl.transitiveVdlDir })
        }

        if (project.hasProperty('clean')) {
            project.clean.delete({ project.vdl.outputPath })
            project.clean.delete({ project.vdl.transitiveVdlDir })
        }

        project.afterEvaluate({
            if (project.plugins.hasPlugin('java')) {
                // Add VDL files in VDL input paths to project resources.
                project.vdl.inputPaths.each {
                    project.sourceSets.main.resources.srcDirs(it).include('**/*.vdl')
                    project.sourceSets.main.resources.srcDirs(it).include('**/vdl.config')
                }

                // Ensure that empty directories are not copied.
                project.tasks.withType(ProcessResources, {
                    it.setIncludeEmptyDirs(false)
                })
            }
        })
    }

    public static String getVdlRootPath(Project project) {
        if (null == project.vdl.vdlRootPath || "".equals(project.vdl.vdlRootPath)) {
            // Look in the transitive VDL files, it should be there so long as the project depends
            // on VDL.
            File candidateVdlRoot = new File(
                    new File(project.getProjectDir(), project.vdl.transitiveVdlDir), 'v.io/v23/vdlroot')
            if (candidateVdlRoot.exists() && candidateVdlRoot.isDirectory()) {
                return candidateVdlRoot.getPath()
            }

            throw new InvalidUserDataException('Could not determine a value for VDLROOT, '
                    + 'you should check to ensure your project depends on vanadium')
        } else {
            return project.vdl.vdlRootPath
        }
    }

    public static Set<String> extractTransitiveVdlFilesAndGetInputPaths(Project project) {
        Set<String> result = new LinkedHashSet<>(project.vdl.inputPaths)
        // Go through the dependencies of all configurations looking for jar files containing
        // VDL files.
        project.configurations.each({
            if (!it.canBeResolved) {
                // Iterating over the configurations that cannot be resolved
                // does not work.
                return
            }
            it.each({
                if (it.getName().endsWith('.jar') && it.exists()) {
                    if (extractVdlFiles(it, new File(project.getProjectDir(), project.vdl.transitiveVdlDir))) {
                        result.add(project.vdl.transitiveVdlDir)
                    }
                }
            })
        })

        // Now recursively descend through the project's project dependencies looking for
        // VDL projects.
        Set<String> projectInputPaths = new LinkedHashSet<>()
        addVdlInputPathsForProject(project, projectInputPaths)
        // Copy any VDL files in any input path to the transitive VDL directory.
        project.copy {
            projectInputPaths.each({
                from(it) {
                    include '**/*.vdl'
                    include '**/vdl.config'
                    includeEmptyDirs = false
                }
            })

            into project.vdl.transitiveVdlDir
        }
        if (!projectInputPaths.isEmpty()) {
            result.add(project.vdl.transitiveVdlDir)
        }
        return result
    }

    public static List<String> getJavaOutDirs(Project project) {
        List<String> result = new ArrayList<>()
        for (String inputPath : project.vdl.inputPaths) {
            if (!inputPath.startsWith(project.vdl.transitiveVdlDir)) {
                result.add(inputPath + '->' + project.vdl.outputPath)
            }
        }
        result.add(project.vdl.transitiveVdlDir + "->" + project.vdl.transitiveVdlDir)
        return result
    }

    /**
     * Extracts any .vdl files in the given jar file to the given destination directory. Returns
     * {@code true} if any files were extracted.
     */
    private static boolean extractVdlFiles(File jarFile, File destination) {
        JarFile jar = new JarFile(jarFile)
        Enumeration<JarEntry> entries = jar.entries()
        boolean extracted = false
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement()

            if (entry.isDirectory()) {
                continue
            }

            if (!entry.getName().endsWith('.vdl') && !entry.getName().endsWith('vdl.config')) {
                continue
            }

            // Create the file's directory.
            File destinationFile = new File(destination, entry.getName())
            destinationFile.getParentFile().mkdirs()
            InputStream input = jar.getInputStream(entry)
            OutputStream output = new FileOutputStream(destinationFile)
            copyStream(input, output)
            extracted = true
            input.close()
            output.close()
        }
        return extracted
    }

    private static void copyStream(InputStream source, OutputStream destination) {
        byte[] buffer = new byte[1 << 16]
        while (true) {
            int r = source.read(buffer)
            if (r == -1) {
                return
            }
            destination.write(buffer, 0, r)
        }
    }

    /**
     * Returns a list of {@link java.io.File} instances representing VDL binaries.
     */
    static List<File> getVdlToolJarFiles(Project project, FileCollection files) {
        List<File> result = new ArrayList<File>()

        for (File file : files.findAll({
            it.name.contains 'gradle-plugin'
        })) {
            project.zipTree(file).findAll({ it.name.startsWith("vdl-") }).each({
                result.add(project.resources.text.fromArchiveEntry(file, it.name).asFile())
            })
        }

        return result
    }

    private static void addVdlInputPathsForProject(Project project, Set<String> inputPaths) {
        if (project == null) {
            return
        }
        Object extension = project.extensions.findByName('vdl')
        if (extension != null) {
            extension.inputPaths.each {
                File newPath = new File(it)
                if (!newPath.isAbsolute()) {
                    newPath = new File(project.getProjectDir(), it)
                }
                inputPaths.add(newPath.getAbsolutePath())
            }
        }
        project.configurations.each({
            it.dependencies.each({
                if (it instanceof ProjectDependency) {
                    addVdlInputPathsForProject(it.getDependencyProject(), inputPaths)
                }
            })
        })
    }

    private static isMacOsX() {
        return System.properties['os.name'].toLowerCase().contains("os x")
    }

    private static isLinux() {
        return System.properties['os.name'].toLowerCase().contains("linux")
    }

    static String getOsName() {
        if (isLinux()) {
            return "linux";
        } else if (isMacOsX()) {
            return "macosx";
        } else {
            throw new IllegalStateException("Unsupported operating system " + System.properties.'os.name')
        }
    }
}

class VdlConfiguration {
    List<String> inputPaths = []
    String vdlRootPath = ""
    String outputPath = "generated-src/vdl"
    String transitiveVdlDir = "generated-src/transitive-vdl"
    String vdlToolPath = ""
    List<String> packageTranslations = ["v.io->io/v"]
    boolean formatGeneratedVdl = true

    // If true, code generated for the vdlroot vdl package will be emitted.
    // Typically, users will want to leave this set to false as they will
    // already get the vdlroot package by depending on the :lib project.
    boolean generateVdlRoot = false;
}

