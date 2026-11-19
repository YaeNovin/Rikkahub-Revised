import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

private val termuxNativeSource = Attribute.of("me.rerere.termux.native-source", String::class.java)

/** Keep the upstream Java API, but use only the JNI implementation built by :workspace. */
class WorkspaceTermuxPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val termuxVersion = extensions.getByType(VersionCatalogsExtension::class.java)
            .named("libs").findVersion("termux").get().requiredVersion
        // This is an artifact attribute: adding it to component variants would prevent
        // Gradle from resolving the dependency graph before transforms can run.
        dependencies.artifactTypes.maybeCreate("aar").attributes.attribute(termuxNativeSource, "upstream")
        dependencies.registerTransform(WorkspaceTermuxAar::class.java) {
            parameters.artifactFileName.set("terminal-emulator-$termuxVersion.aar")
            from.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "aar")
                .attribute(termuxNativeSource, "upstream")
            to.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "aar")
                .attribute(termuxNativeSource, "workspace")
        }
        configurations.configureEach {
            attributes.attribute(termuxNativeSource, "workspace")
        }
    }
}

@CacheableTransform
abstract class WorkspaceTermuxAar : TransformAction<WorkspaceTermuxAar.Parameters> {
    interface Parameters : TransformParameters {
        @get:Input
        val artifactFileName: Property<String>
    }

    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val input = inputArtifact.get().asFile
        if (input.name != parameters.artifactFileName.get()) {
            outputs.file(input)
            return
        }
        stripUpstreamTermuxJni(input, outputs.file("${input.nameWithoutExtension}-workspace-jni.aar"))
    }
}

internal fun stripUpstreamTermuxJni(input: File, output: File) {
    ZipFile(input).use { source ->
        ZipOutputStream(output.outputStream().buffered()).use { destination ->
            source.entries().asSequence().forEach { entry ->
                if (entry.name.startsWith("jni/") && entry.name.endsWith("/libtermux.so")) return@forEach
                destination.putNextEntry(ZipEntry(entry.name).apply { time = 0L })
                if (!entry.isDirectory) source.getInputStream(entry).use { it.copyTo(destination) }
                destination.closeEntry()
            }
        }
    }
}
