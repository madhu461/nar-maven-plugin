/*
 * #%L
 * Native ARchive plugin for Maven
 * %%
 * Copyright (C) 2002 - 2014 NAR Maven Plugin developers.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package com.github.maven_nar;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipInputStream;
import org.apache.commons.io.IOUtils;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.shared.artifact.filter.collection.ArtifactFilterException;
import org.apache.maven.shared.artifact.filter.collection.ArtifactIdFilter;
import org.apache.maven.shared.artifact.filter.collection.FilterArtifacts;
import org.apache.maven.shared.artifact.filter.collection.GroupIdFilter;
import org.apache.maven.shared.artifact.filter.collection.ScopeFilter;
import org.codehaus.plexus.archiver.manager.ArchiverManager;
import org.codehaus.plexus.util.StringUtils;

import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilderException;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;

/**
 * @author Mark Donszelmann
 */
public abstract class AbstractDependencyMojo extends AbstractNarMojo {

  @Parameter(defaultValue = "${localRepository}", required = true, readonly = true)
  private ArtifactRepository localRepository;

  /**
   * Artifact resolver, needed to download the attached nar files.
   */
  @Component(role = org.apache.maven.artifact.resolver.ArtifactResolver.class)
  protected ArtifactResolver artifactResolver;

  /**
   * Remote repositories which will be searched for nar attachments.
   */
  @Parameter(defaultValue = "${project.remoteArtifactRepositories}", required = true, readonly = true)
  protected List remoteArtifactRepositories;

  /**
   * Comma separated list of Artifact names to exclude.
   * 
   * @since 2.0
   */
  @Parameter(property = "excludeArtifactIds", defaultValue = "")
  protected String excludeArtifactIds;

  /**
   * Comma separated list of Artifact names to include.
   * 
   * @since 2.0
   */
  @Parameter(property = "includeArtifactIds", defaultValue = "")
  protected String includeArtifactIds;

  /**
   * Comma separated list of GroupId Names to exclude.
   * 
   * @since 2.0
   */
  @Parameter(property = "excludeGroupIds", defaultValue = "")
  protected String excludeGroupIds;

  /**
   * Comma separated list of GroupIds to include.
   * 
   * @since 2.0
   */
  @Parameter(property = "includeGroupIds", defaultValue = "")
  protected String includeGroupIds;

  /**
   * The computed dependency tree root node of the Maven project.
   */
  protected  DependencyNode rootNode;

  /**
   * The dependency tree builder to use.
   */
  @Component( hint = "default" )
  protected  DependencyGraphBuilder dependencyGraphBuilder;

  /**
   * The computed dependency tree root node of the Maven project.
   */
  private List<DependencyNode> LevelOrderList = new ArrayList<DependencyNode>();

  /**
   * Serializes the dependency tree of current maven project to a string of comma separated list
   * of groupId:artifactId traversing nodes in Level-Order way (also called BFS algorithm)
   *
   * @return Dependency tree string of comma separated list
   * of groupId:artifactId
   */
  protected String dependencyTreeOrderStr()
  {
    String depLevelOrderStr = "";
    DependencyNode libTreeRootNode;

    try {
      libTreeRootNode = getRootNodeDependecyTree();
    } catch (MojoExecutionException exception) {
      this.getLog().warn("linker Nar Default DependencyLibOrder is not used");
      return depLevelOrderStr;
    }

    final List<DependencyNode> NodeList = depLevelOrderList(libTreeRootNode);

    this.getLog().debug("{");
    this.getLog().debug("Dependency Lib Order to be used::");

    for (DependencyNode node : NodeList) {

      if ( node != null ) {
        String[] nodestring = node.toNodeString().split(":");
        String usestring = nodestring[0] + ":" + nodestring[1];

        this.getLog().debug(usestring);

        if (!depLevelOrderStr.isEmpty()){
          depLevelOrderStr= depLevelOrderStr + "," + usestring;
        }else{
          depLevelOrderStr= usestring;
        }

      }
    }

    this.getLog().debug("}");

    return depLevelOrderStr;
  }

  /**
   * Get List of Nodes of Dependency tree serialised by traversing nodes
   * in Level-Order way (also called BFS algorithm)
   *
   * @param rootNode root node of the project Dependency tree
   * @return the dependency tree string of comma separated list
   * of groupId:artifactId
   */
  private List<DependencyNode> depLevelOrderList(DependencyNode rootNode )
  {

    List<DependencyNode> NodeChildList = rootNode.getChildren();
    //LevelOrderList.add(rootNode);

    while (!NodeChildList.isEmpty()) {
      NodeChildList = levelTraverseTreeList(NodeChildList);
    }

    return this.LevelOrderList;
  }

  /**
   * helper function for traversing nodes
   * in Level-Order way (also called BFS algorithm)
   */
  private List<DependencyNode> levelTraverseTreeList(List<DependencyNode>  NodeList )
  {
    this.LevelOrderList.addAll(NodeList);
    List<DependencyNode> NodeChildList = new ArrayList<DependencyNode>();
    for (DependencyNode node : NodeList) {
      if ( (node != null) && (node.getChildren() != null) ) {
        NodeChildList.addAll(node.getChildren());
      }
    }

    return NodeChildList;
  }


  //idea from dependency:tree mojo
  /**
   * Get root node of the current Maven project Dependency tree generated by
   * maven.shared dependency graph builder.
   *
   * @return root node of the project Dependency tree
   * @throws MojoExecutionException
   */
  private DependencyNode getRootNodeDependecyTree() throws MojoExecutionException{
    try {
      ArtifactFilter artifactFilter = null;

      // works for only maven 3. Use of dependency graph component not handled for maven 2
      // as current version of NAR already requires Maven 3.x
      rootNode = dependencyGraphBuilder.buildDependencyGraph(getMavenProject(), artifactFilter);

    } catch (DependencyGraphBuilderException exception) {
      throw new MojoExecutionException("Cannot build project dependency graph", exception);
    }

    return rootNode;
  }

  /**
   * To look up Archiver/UnArchiver implementations
   */
  @Component(role = org.codehaus.plexus.archiver.manager.ArchiverManager.class)
  protected ArchiverManager archiverManager;

  public final void downloadAttachedNars(final List<AttachedNarArtifact> dependencies)
      throws MojoExecutionException, MojoFailureException {
    getLog().debug("Download for NarDependencies {");
    for (final AttachedNarArtifact attachedNarArtifact : dependencies) {
      getLog().debug("  - " + attachedNarArtifact);
    }
    getLog().debug("}");

    for (final AttachedNarArtifact attachedNarArtifact : dependencies) {
      try {
        getLog().debug("Resolving " + attachedNarArtifact);
        this.artifactResolver.resolve(attachedNarArtifact, this.remoteArtifactRepositories, getLocalRepository());
      } catch (final ArtifactNotFoundException e) {
        final String message = "nar not found " + attachedNarArtifact.getId();
        throw new MojoExecutionException(message, e);
      } catch (final ArtifactResolutionException e) {
        final String message = "nar cannot resolve " + attachedNarArtifact.getId();
        throw new MojoExecutionException(message, e);
      }
    }
  }

  public final List<AttachedNarArtifact> getAllAttachedNarArtifacts(final List<NarArtifact> narArtifacts,
      List<? extends Executable> libraries) throws MojoExecutionException, MojoFailureException {
    final List<AttachedNarArtifact> artifactList = new ArrayList<>();
    for (NarArtifact dependency : narArtifacts) {
      if ("NAR".equalsIgnoreCase(getMavenProject().getPackaging())) {
        final String bindings[] = getBindings(libraries, dependency);

        // TODO: dependency.getFile(); find out what the stored pom says
        // about this - what nars should exist, what layout are they
        // using...
        for (final String binding : bindings) {
          artifactList.addAll(getAttachedNarArtifacts(dependency, /* library. */
              getAOL(), binding));
        }
      } else {
        artifactList.addAll(getAttachedNarArtifacts(dependency, getAOL(), Library.EXECUTABLE));
        artifactList.addAll(getAttachedNarArtifacts(dependency, getAOL(), Library.SHARED));
        artifactList.addAll(getAttachedNarArtifacts(dependency, getAOL(), Library.JNI));
        artifactList.addAll(getAttachedNarArtifacts(dependency, getAOL(), Library.STATIC));
      }
      artifactList.addAll(getAttachedNarArtifacts(dependency, null, NarConstants.NAR_NO_ARCH));
    }
    return artifactList;
  }

  protected final ArchiverManager getArchiverManager() {
    return this.archiverManager;
  }

  /**
   * Returns the artifacts which must be taken in account for the Mojo.
   * 
   * @return Artifacts
   */
  protected abstract ScopeFilter getArtifactScopeFilter();

  /**
   * Returns the attached NAR Artifacts (AOL and noarch artifacts) from the NAR
   * dependencies artifacts of the project.
   * The artifacts which will be processed are those returned by the method
   * getArtifacts() which must be implemented
   * in each class which extends AbstractDependencyMojo.
   * 
   * @return Attached NAR Artifacts
   * @throws MojoFailureException
   * @throws MojoExecutionException
   * 
   * @see getArtifacts
   */
  protected List<AttachedNarArtifact> getAttachedNarArtifacts(List<? extends Executable> libraries)
      throws MojoFailureException, MojoExecutionException {
    getLog().info("Getting Nar dependencies");
    final List<NarArtifact> narArtifacts = getNarArtifacts();
    final List<AttachedNarArtifact> attachedNarArtifacts = getAllAttachedNarArtifacts(narArtifacts, libraries);
    return attachedNarArtifacts;
  }

  private List<AttachedNarArtifact> getAttachedNarArtifacts(final NarArtifact dependency, final AOL aol,
      final String type) throws MojoExecutionException, MojoFailureException {
    getLog().debug("GetNarDependencies for " + dependency + ", aol: " + aol + ", type: " + type);
    final List<AttachedNarArtifact> artifactList = new ArrayList<>();
    final NarInfo narInfo = dependency.getNarInfo();
    final String[] nars = narInfo.getAttachedNars(aol, type);
    // FIXME Move this to NarInfo....
    if (nars != null) {
      for (final String nar2 : nars) {
        getLog().debug("    Checking: " + nar2);
        if (nar2.equals("")) {
          continue;
        }
        final String[] nar = nar2.split(":", 5);
        if (nar.length >= 4) {
          try {
            final String groupId = nar[0].trim();
            final String artifactId = nar[1].trim();
            final String ext = nar[2].trim();
            String classifier = nar[3].trim();
            // translate for instance g++ to gcc...
            final AOL aolString = narInfo.getAOL(aol);
            if (aolString != null) {
              classifier = NarUtil.replace("${aol}", aolString.toString(), classifier);
            }
            final String version = nar.length >= 5 ? nar[4].trim() : dependency.getBaseVersion();
            artifactList.add(new AttachedNarArtifact(groupId, artifactId, version, dependency.getScope(), ext,
                classifier, dependency.isOptional(), dependency.getFile()));
          } catch (final InvalidVersionSpecificationException e) {
            throw new MojoExecutionException("Error while reading nar file for dependency " + dependency, e);
          }
        } else {
          getLog().warn("nars property in " + dependency.getArtifactId() + " contains invalid field: '" + nar2);
        }
      }
    }
    return artifactList;
  }

  protected String[] getBindings(List<? extends Executable> libraries, NarArtifact dependency)
      throws MojoFailureException, MojoExecutionException {

    Set<String> bindings = new HashSet<>();
    if (libraries != null){
      for (Object library : libraries) {
        Executable exec = (Executable) library;
        // how does this project specify the dependency is used
        String binding = exec.getBinding(dependency);
        if( null != binding )
          bindings.add(binding);
      }
    }

    // - if it is specified but the atrifact is not available should fail.
    // otherwise
    // how does the artifact specify it should be used by default
    // -
    // whats the preference for this type of library to use (shared - shared,
    // static - static...)

    // library.getType()
    if (bindings.isEmpty())
      bindings.add(dependency.getNarInfo().getBinding(getAOL(), Library.STATIC));

    return bindings.toArray(new String[1]);
  }

  protected String getBinding(Executable exec, NarArtifact dependency)
      throws MojoFailureException, MojoExecutionException {

    // how does this project specify the dependency is used
    String binding = exec.getBinding(dependency);

    // - if it is specified but the atrifact is not available should fail.
    // otherwise
    // how does the artifact specify it should be used by default
    // -
    // whats the preference for this type of library to use (shared - shared,
    // static - static...)

    // library.getType()
    if (binding == null)
      binding = dependency.getNarInfo().getBinding(getAOL(), Library.STATIC);

    return binding;
  }

  /**
   * The plugin remote repositories declared in the pom.
   * 
   * @since 2.2
   */
  // @Parameter(defaultValue = "${project.pluginArtifactRepositories}")
  // private List remotePluginRepositories;

  protected final ArtifactRepository getLocalRepository() {
    return this.localRepository;
  }

  /**
   * Returns dependencies which are dependent on NAR files (i.e. contain
   * NarInfo)
   */
  public final List<NarArtifact> getNarArtifacts() throws MojoExecutionException {
    final List<NarArtifact> narDependencies = new LinkedList<>();

    FilterArtifacts filter = new FilterArtifacts();

    filter.addFilter(new GroupIdFilter(cleanToBeTokenizedString(this.includeGroupIds),
        cleanToBeTokenizedString(this.excludeGroupIds)));

    filter.addFilter(new ArtifactIdFilter(cleanToBeTokenizedString(this.includeArtifactIds),
        cleanToBeTokenizedString(this.excludeArtifactIds)));

    filter.addFilter(getArtifactScopeFilter());

    @SuppressWarnings("unchecked")
    Set<Artifact> artifacts = getMavenProject().getArtifacts();

    // perform filtering
    try {
      artifacts = filter.filter(artifacts);
    } catch (ArtifactFilterException e) {
      throw new MojoExecutionException(e.getMessage(), e);
    }

    for (final Object element : artifacts) {
      final Artifact dependency = (Artifact) element;

      if ("nar".equalsIgnoreCase(dependency.getType())) {
        getLog().debug("Examining artifact for NarInfo: " + dependency);

        final NarInfo narInfo = getNarInfo(dependency);
        if (narInfo != null) {
          getLog().debug("    - added as NarDependency");
          narDependencies.add(new NarArtifact(dependency, narInfo));
        }
      }
    }
    getLog().debug("Dependencies contained " + narDependencies.size() + " NAR artifacts.");
    return narDependencies;
  }

  public final NarInfo getNarInfo(final Artifact dependency) throws MojoExecutionException {
    // FIXME reported to maven developer list, isSnapshot changes behaviour
    // of getBaseVersion, called in pathOf.
    dependency.isSnapshot();

    if (dependency.getFile().isDirectory()) {
      getLog().debug("Dependency is not packaged: " + dependency.getFile());

      return new NarInfo(dependency.getGroupId(), dependency.getArtifactId(), dependency.getBaseVersion(), getLog(),
          dependency.getFile());
    }

    final File file = new File(getLocalRepository().getBasedir(), getLocalRepository().pathOf(dependency));
    if (!file.exists()) {
      getLog().debug("Dependency nar file does not exist: " + file);
      return null;
    }

    ZipInputStream zipStream = null;
    try {
      zipStream = new ZipInputStream(new FileInputStream(file));
      if (zipStream.getNextEntry() == null) {
        getLog().debug("Skipping unreadable artifact: " + file);
        return null;
      }
    } catch (IOException e) {
      throw new MojoExecutionException("Error while testing for zip file " + file, e);
    } finally {
      IOUtils.closeQuietly(zipStream);
    }

    JarFile jar = null;
    try {
      jar = new JarFile(file);
      final NarInfo info = new NarInfo(dependency.getGroupId(), dependency.getArtifactId(),
          dependency.getBaseVersion(), getLog());
      if (!info.exists(jar)) {
        getLog().debug("Dependency nar file does not contain this artifact: " + file);
        return null;
      }
      info.read(jar);
      return info;
    } catch (final IOException e) {
      throw new MojoExecutionException("Error while reading " + file, e);
    } finally {
      IOUtils.closeQuietly(jar);
    }
  }

  protected final NarManager getNarManager() throws MojoFailureException, MojoExecutionException {
    return new NarManager(getLog(), getLocalRepository(), getMavenProject(), getArchitecture(), getOS(), getLinker());
  }

  protected final List/* <ArtifactRepository> */getRemoteRepositories() {
    return this.remoteArtifactRepositories;
  }

  public final void unpackAttachedNars(final List<AttachedNarArtifact> dependencies)
      throws MojoExecutionException, MojoFailureException {
    final File unpackDir = getUnpackDirectory();

    getLog().info(String.format("Unpacking %1$d dependencies to %2$s", dependencies.size(), unpackDir));

    for (final Object element : dependencies) {
      final AttachedNarArtifact dependency = (AttachedNarArtifact) element;
      final File file = getNarManager().getNarFile(dependency); // dependency.getNarFile();
      getLog().debug(String.format("Unpack %1$s (%2$s) to %3$s", dependency, file, unpackDir));

      // TODO: each dependency may have it's own (earlier) version of layout -
      // if it is unknown then we should report an error to update the nar
      // package
      // NarLayout layout = AbstractNarLayout.getLayout( "NarLayout21"/* TODO:
      // dependency.getLayout() */, getLog() );
      // we should then target the layout to match the layout for this nar which
      // is the workspace we are in.
      final NarLayout layout = getLayout();
      // TODO: the dependency may be specified against a different linker
      // (version)?
      // AOL aol = dependency.getClassifier(); Trim
      layout.unpackNar(unpackDir, this.archiverManager, file, getOS(), getLinker().getName(), getAOL());
    }
  }

  //
  // clean up configuration string before it can be tokenized
  //
  private static String cleanToBeTokenizedString(String str) {
    String ret = "";
    if (!StringUtils.isEmpty(str)) {
      // remove initial and ending spaces, plus all spaces next to commas
      ret = str.trim().replaceAll("[\\s]*,[\\s]*", ",");
    }

    return ret;
  }
}
