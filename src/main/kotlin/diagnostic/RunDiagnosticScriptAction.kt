package diagnostic

import com.intellij.diagnostic.VMOptions
import com.intellij.execution.CantRunException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.JavaCommandLineState
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.ModuleRunProfile
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.ex.ApplicationManagerEx
import com.intellij.openapi.components.AbstractProjectComponent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.impl.NonProjectFileWritingAccessExtension
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.projectRoots.ex.PathUtilEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.PathUtil
import com.intellij.util.net.NetUtils
import org.kohsuke.youdebug.YouDebug
import java.io.File
import java.lang.management.ManagementFactory
import java.util.*

class RunDiagnosticScriptAction : DumbAwareAction() {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val file = file() ?: return

    val originalText = VMOptions.read() ?: ""

    if (originalText.contains("jdwp")) {
      LOG.warn("IDE already configured in debug mode")
      Messages.showInfoMessage(project, "IDE already configured in debug mode", "Cannot execute debug script")
      return
    }

    FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFileDescriptor("diagnostic")
                               .withTitle("Select diagnostic script"),
                           project,
                           null) {
      LOG.info("Debug script: ${it.canonicalPath}")

      val toClose = ProjectManager.getInstance().openProjects.filter { it != project }

      val message = "This will " +
          (if (toClose.isEmpty()) "" else "close all projects except ${project.name}, ") +
          "restart your IDE with a temporary configuration, and then execute '" +
          it.presentableName + "'. You should then reproduce " +
          "your problem and send the output to us. Do you want to proceed?"

      val result = Messages.showYesNoDialog(project,
                                            message,
                                            "Execute debug script?",
                                            Messages.getQuestionIcon())
      if (result == Messages.YES) {
        if (file.exists()) {
          val backup = File(file.parent, file.name + backupExtension)
          LOG.info("Backing up ${file.canonicalPath} to ${backup.canonicalPath}")
          FileUtil.copy(file, backup)
        }

        val port = NetUtils.findAvailableSocketPort();

        LOG.info("Debugger port: $port")

        FileUtil.writeToFile(file, originalText +
            "\n-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=" +
            port + "\n")

        toClose.forEach {
          LOG.info("Closing ${it.name}")
          ProjectManager.getInstance().closeProject(it)
        }

        PropertiesComponent.getInstance(project).setValue(scriptProperty, it.canonicalPath);

        ApplicationManagerEx.getApplicationEx().restart(true)
      }
    }
  }

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabled = e.project != null && file() != null
  }

  private companion object {
    private val LOG = Logger.getInstance(RunDiagnosticScriptAction::class.java)
    private val PATHS_SELECTOR = System.getProperty(PathManager.PROPERTY_PATHS_SELECTOR);

    private val scriptProperty = "diagnostic.script.path"
    private val backupExtension = ".diagnostic.backup"

    fun file(): File? {
      val dir = try {
        val method = PathManager::class.java.getMethod("getCustomOptionsDirectory")
        method.invoke(null) as String?
      } catch (e: NoSuchMethodException) {
        getCustomOptionsDirectory()
      }

      val filename = if (SystemInfo.isMac)
        "idea.vmoptions"
      else try {
        val method = VMOptions::class.java.getMethod("getCustomFileName")
        method.invoke(null) as String
      } catch(e: NoSuchMethodException) {
        getCustomFileName()
      }

      return if (dir != null) File(dir, filename) else null
    }

    // Copied from v15 for older versions which don't supply this method
    fun getCustomOptionsDirectory(): String? {
      val platformPathMethod = PathManager::class.java.getDeclaredMethod("platformPath",
                                                                         String::class.java,
                                                                         String::class.java,
                                                                         String::class.java)
      return if (PATHS_SELECTOR != null) {
        platformPathMethod.isAccessible = true
        platformPathMethod.invoke(null, PATHS_SELECTOR, "Library/Preferences", "") as String?
      } else null
    }

    // Copied from v15 for older versions which don't supply this method
    fun getCustomFileName(): String {
      val name = ApplicationNamesInfo.getInstance().productName.toLowerCase(Locale.US)
      val platformSuffix = if (SystemInfo.is64Bit) "64" else ""
      val osSuffix = if (SystemInfo.isWindows) ".exe" else ""
      return name + platformSuffix + osSuffix + ".vmoptions"
    }
  }

  class Component(val project: Project) : AbstractProjectComponent(project) {
    override fun projectOpened() {
      val scriptPath = PropertiesComponent.getInstance(project).getValue(scriptProperty) ?: return

      LOG.info("Debug script path found: $scriptPath")

      val file = file() ?: return
      val script = File(scriptPath)

      if (!script.exists()) {
        LOG.warn("Debug script not found: ${script.canonicalPath}")
        cleanup(file)
        return
      }

      val target = getTarget()
      if (target == null) {
        cleanup(file)
        LOG.warn("IDE not running in debug mode")
        Messages.showInfoMessage(project, "IDE not running in debug mode", "Cannot execute debug script")
        ApplicationManagerEx.getApplicationEx().restart(true)
      }

      LOG.info("Debug target: $target")

      val adapter: ProcessAdapter = object : ProcessAdapter() {
        override fun processTerminated(event: ProcessEvent?) {
          LOG.info("Debug script terminated")

          cleanup(file)

          ApplicationManager.getApplication().invokeLater {
            val result = Messages.showYesNoDialog(project,
                                                  "The debug process has terminated, and the temporary configuration " +
                                                      "has been removed. Restart IntelliJ now?",
                                                  "Restart IDE?",
                                                  Messages.getQuestionIcon())
            if (result == Messages.YES) {
              ApplicationManagerEx.getApplicationEx().restart(true)
            }
          }
        }
      }

      val profile = RunProfile(scriptPath, target!!, adapter)
      ExecutionEnvironmentBuilder.create(myProject, DefaultRunExecutor.getRunExecutorInstance(), profile).buildAndExecute()
    }

    private fun cleanup(file: File) {
      val backupFile = File(file.parent, file.name + backupExtension)

      if (backupFile.exists()) {
        backupFile.renameTo(file)
      } else {
        if (file.exists()) {
          file.delete()
        }
      }

      PropertiesComponent.getInstance(project).unsetValue(scriptProperty)
    }

    private fun getTarget(): String? {
      for (argument in ManagementFactory.getRuntimeMXBean().inputArguments) {
        if (argument.startsWith("-agentlib:jdwp") && argument.contains("transport=dt_socket")) {
          val params = argument.split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
          for (param in params) {
            if (param.startsWith("address")) {
              try {
                val address = param.split("=".toRegex()).dropLastWhile { it.isEmpty() }[1]
                val details = address.split(":".toRegex()).dropLastWhile { it.isEmpty() }
                if (details.size == 1) {
                  return "localhost:${details[0]}"
                } else {
                  return address
                }
              } catch (e: Exception) {
                LOG.error(e)
                return null
              }
            }
          }
          break
        }
      }
      return null
    }
  }

  class RunProfile(val scriptPath: String, val target: String, val adapter: ProcessAdapter) : ModuleRunProfile {
    override fun getState(executor: Executor, environment: ExecutionEnvironment) = CommandLineState(scriptPath, target, adapter, environment)
    override fun getIcon() = null
    override fun getName() = "Debug Script"
    override fun getModules() = Module.EMPTY_ARRAY
  }

  class CommandLineState(val scriptPath: String, val target: String, val adapter: ProcessAdapter, val env: ExecutionEnvironment) : JavaCommandLineState(env) {
    override fun createJavaParameters(): JavaParameters? {
      val parameters = JavaParameters()
      val jdk = PathUtilEx.getAnyJdk(env.project) ?: throw CantRunException.noJdkConfigured()
      parameters.jdk = jdk

      val jarPath = PathUtil.getJarPathForClass(YouDebug::class.java)
      val jarDir = File(jarPath).parentFile
      val jars = jarDir.listFiles().filter { !it.name.contains("kotlin") }

      parameters.classPath.addAll(jars.map { it.canonicalPath })
      parameters.mainClass = "org.kohsuke.youdebug.YouDebug"

      parameters.programParametersList.addAll("-socket", target, scriptPath)
      parameters.workingDirectory = env.project.basePath
      return parameters
    }

    override fun startProcess(): OSProcessHandler {
      val result = super.startProcess()
      result.addProcessListener(adapter)
      return result
    }
  }

  class AccessExtension : NonProjectFileWritingAccessExtension {
    override fun isWritable(file: VirtualFile): Boolean = FileUtil.pathsEqual(file.path, Companion.file()?.path)
  }
}
