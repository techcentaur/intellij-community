package org.jetbrains.idea.maven.wizards;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathMacros;
import com.intellij.openapi.module.ModifiableModuleModel;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.roots.ui.configuration.ModulesProvider;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.projectImport.ProjectImportBuilder;
import org.apache.maven.model.Model;
import org.apache.maven.model.Profile;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.maven.project.MavenGeneralSettings;
import org.jetbrains.idea.maven.embedder.MavenEmbedderFactory;
import org.jetbrains.idea.maven.embedder.MavenEmbedderWrapper;
import org.jetbrains.idea.maven.project.*;
import org.jetbrains.idea.maven.runner.SoutMavenConsole;
import org.jetbrains.idea.maven.utils.FileFinder;

import javax.swing.*;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * @author Vladislav.Kaznacheev
 */
public class MavenProjectBuilder extends ProjectImportBuilder<MavenProjectModel> {
  private final static Icon ICON = IconLoader.getIcon("/images/mavenEmblem.png");

  private Project myProjectToUpdate;

  private MavenGeneralSettings myGeneralSettingsCache;
  private MavenImportingSettings myImportingSettingsCache;
  private MavenDownloadingSettings myDownloadingSettingsCache;

  private VirtualFile myImportRoot;
  private List<VirtualFile> myFiles;
  private List<String> myProfiles = new ArrayList<String>();
  private List<String> mySelectedProfiles = new ArrayList<String>();

  private MavenProjectsTree myMavenProjectTree;

  private boolean myOpenModulesConfigurator;

  public String getName() {
    return ProjectBundle.message("maven.name");
  }

  public Icon getIcon() {
    return ICON;
  }

  public void cleanup() {
    super.cleanup();
    myMavenProjectTree = null;
    myImportRoot = null;
    myProjectToUpdate = null;
  }

  @Override
  public boolean validate(Project current, Project dest) {
    return true;
  }

  public List<Module> commit(final Project project, final ModifiableModuleModel model, final ModulesProvider modulesProvider) {
    project.getComponent(MavenWorkspaceSettingsComponent.class).getState().generalSettings = getGeneralSettings();
    project.getComponent(MavenWorkspaceSettingsComponent.class).getState().importingSettings = getImportingSettings();
    project.getComponent(MavenWorkspaceSettingsComponent.class).getState().downloadingSettings = getDownloadingSettings();

    MavenProjectsManager manager = MavenProjectsManager.getInstance(project);

    manager.setManagedFiles(myFiles);
    manager.setActiveProfiles(mySelectedProfiles);
    manager.setImportedMavenProjectModelManager(myMavenProjectTree);
    final List<Module> moduleList = manager.commit(model, modulesProvider);

    enusreRepositoryPathMacro();
    return moduleList;
  }

  private void enusreRepositoryPathMacro() {
    final File repo = getGeneralSettings().getEffectiveLocalRepository();
    final PathMacros macros = PathMacros.getInstance();

    for (String each : macros.getAllMacroNames()) {
      String path = macros.getValue(each);
      if (path == null) continue;
      if (new File(path).equals(repo)) return;
    }
    ApplicationManager.getApplication().runWriteAction(new Runnable() {
      public void run() {
        macros.setMacro("MAVEN_REPOSITORY", repo.getPath(), null);
      }
    });
  }

  public Project getUpdatedProject() {
    return getProjectToUpdate();
  }

  public VirtualFile getRootDirectory() {
    return getImportRoot();
  }

  public boolean setRootDirectory(final String root) throws ConfigurationException {
    myFiles = null;
    myProfiles.clear();
    myMavenProjectTree = null;

    myImportRoot = FileFinder.refreshRecursively(root);
    if (getImportRoot() == null) return false;

    return runConfigurationProcess(ProjectBundle.message("maven.scanning.projects"), new MavenProcess.MavenTask() {
      public void run(MavenProcess p) throws MavenProcessCanceledException {
        p.setText(ProjectBundle.message("maven.locating.files"));
        myFiles = FileFinder.findPomFiles(getImportRoot().getChildren(), getImportingSettings().isLookForNested(), p.getIndicator(),
                                          new ArrayList<VirtualFile>());

        collectProfiles(p);

        if (myProfiles.isEmpty()) {
          readMavenProjectTree(p);
        }

        p.setText2("");
      }
    });
  }

  private void collectProfiles(MavenProcess p) {
    myProfiles = new ArrayList<String>();

    MavenEmbedderWrapper e = MavenEmbedderFactory.createEmbedderForRead(getGeneralSettings(), new SoutMavenConsole());
    try {
      for (VirtualFile f : myFiles) {
        MavenProjectHolder holder = MavenReader.readProject(e, f, new ArrayList<String>(), p);
        if (!holder.isValid) continue;

        Set<String> profiles = new LinkedHashSet<String>();
        collectProfileIds(holder.mavenProject.getModel(), profiles);
        if (!profiles.isEmpty()) myProfiles.addAll(profiles);
      }
    }
    catch (MavenProcessCanceledException ignore) {
    }
    finally {
      e.release();
    }
  }

  public static Set<String> collectProfileIds(Model mavenModel, Set<String> profileIds) {
    for (Profile profile : (List<Profile>)mavenModel.getProfiles()) {
      profileIds.add(profile.getId());
    }
    return profileIds;
  }

  public List<String> getProfiles() {
    return myProfiles;
  }

  public List<String> getSelectedProfiles() {
    return mySelectedProfiles;
  }

  public boolean setSelectedProfiles(List<String> profiles) throws ConfigurationException {
    myMavenProjectTree = null;
    mySelectedProfiles = profiles;

    return runConfigurationProcess(ProjectBundle.message("maven.scanning.projects"), new MavenProcess.MavenTask() {
      public void run(MavenProcess p) throws MavenProcessCanceledException {
        readMavenProjectTree(p);
        p.setText2("");
      }
    });
  }

  private boolean runConfigurationProcess(String message, MavenProcess.MavenTask p) throws ConfigurationException {
    try {
      MavenProcess.run(null, message, p);
    }
    catch (MavenProcessCanceledException e) {
      return false;
    }

    return true;
  }

  private void readMavenProjectTree(MavenProcess p) throws MavenProcessCanceledException {
    myMavenProjectTree = new MavenProjectsTree();
    myMavenProjectTree.read(myFiles, mySelectedProfiles, getGeneralSettings(), new SoutMavenConsole(), p);
  }

  public List<MavenProjectModel> getList() {
    return myMavenProjectTree.getRootProjects();
  }

  public boolean isMarked(final MavenProjectModel element) {
    return true;
  }

  public void setList(List<MavenProjectModel> nodes) throws ConfigurationException {
    for (MavenProjectModel node : myMavenProjectTree.getRootProjects()) {
      node.setIncluded(nodes.contains(node));
    }
  }

  public boolean isOpenProjectSettingsAfter() {
    return myOpenModulesConfigurator;
  }

  public void setOpenProjectSettingsAfter(boolean on) {
    myOpenModulesConfigurator = on;
  }

  public MavenGeneralSettings getGeneralSettings() {
    if (myGeneralSettingsCache == null) {
      myGeneralSettingsCache = getProject().getComponent(MavenWorkspaceSettingsComponent.class).getState().generalSettings.clone();
    }
    return myGeneralSettingsCache;
  }

  public MavenImportingSettings getImportingSettings() {
    if (myImportingSettingsCache == null) {
      myImportingSettingsCache = getProject().getComponent(MavenWorkspaceSettingsComponent.class).getState().importingSettings.clone();
    }
    return myImportingSettingsCache;
  }

  private MavenDownloadingSettings getDownloadingSettings() {
    if (myDownloadingSettingsCache == null) {
      myDownloadingSettingsCache = getProject().getComponent(MavenWorkspaceSettingsComponent.class).getState().downloadingSettings.clone();
    }
    return myDownloadingSettingsCache;
  }

  private Project getProject() {
    return isUpdate() ? getProjectToUpdate() : ProjectManager.getInstance().getDefaultProject();
  }

  public void setFiles(List<VirtualFile> files) {
    myFiles = files;
  }

  @Nullable
  public Project getProjectToUpdate() {
    if (myProjectToUpdate == null) {
      myProjectToUpdate = getCurrentProject();
    }
    return myProjectToUpdate;
  }

  @Nullable
  public VirtualFile getImportRoot() {
    if (myImportRoot == null && isUpdate()) {
      final Project project = getProjectToUpdate();
      assert project != null;
      myImportRoot = project.getBaseDir();
    }
    return myImportRoot;
  }

  public String getSuggestedProjectName() {
    final List<MavenProjectModel> list = myMavenProjectTree.getRootProjects();
    if (list.size() == 1) {
      return list.get(0).getMavenId().artifactId;
    }
    return null;
  }
}
