// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.roots.impl;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileTypes.FileTypeRegistry;
import com.intellij.openapi.fileTypes.impl.FileTypeAssocTable;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.UnloadedModuleDescription;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.roots.impl.libraries.LibraryEx;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Conditions;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWithId;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.vfs.pointers.VirtualFilePointer;
import com.intellij.util.CollectionQuery;
import com.intellij.util.Function;
import com.intellij.util.Query;
import com.intellij.util.containers.Stack;
import com.intellij.util.containers.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.model.fileTypes.FileNameMatcherFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.*;

public class RootIndex {
  static final Comparator<OrderEntry> BY_OWNER_MODULE = (o1, o2) -> {
    String name1 = o1.getOwnerModule().getName();
    String name2 = o2.getOwnerModule().getName();
    return name1.compareTo(name2);
  };

  private static final Logger LOG = Logger.getInstance("#com.intellij.openapi.roots.impl.RootIndex");
  private static final FileTypeRegistry ourFileTypes = FileTypeRegistry.getInstance();

  private final Map<VirtualFile, String> myPackagePrefixByRoot = ContainerUtil.newHashMap();

  private final Map<VirtualFile, DirectoryInfo> myRootInfos = ContainerUtil.newHashMap();
  private final ConcurrentBitSet myNonInterestingIds = new ConcurrentBitSet();
  @NotNull private final Project myProject;
  private final PackageDirectoryCache myPackageDirectoryCache;
  private OrderEntryGraph myOrderEntryGraph;

  public RootIndex(@NotNull Project project) {
    myProject = project;

    ApplicationManager.getApplication().assertReadAccessAllowed();

    final RootInfo info = buildRootInfo(project);

    MultiMap<String, VirtualFile> rootsByPackagePrefix = MultiMap.create();
    Set<VirtualFile> allRoots = info.getAllRoots();
    for (VirtualFile root : allRoots) {
      List<VirtualFile> hierarchy = getHierarchy(root, allRoots, info);
      Pair<DirectoryInfo, String> pair = hierarchy != null
                                         ? calcDirectoryInfo(root, hierarchy, info)
                                         : new Pair<>(NonProjectDirectoryInfo.IGNORED, null);
      myRootInfos.put(root, pair.first);
      rootsByPackagePrefix.putValue(pair.second, root);
      myPackagePrefixByRoot.put(root, pair.second);
    }
    myPackageDirectoryCache = new PackageDirectoryCache(rootsByPackagePrefix) {
      @Override
      protected boolean isPackageDirectory(@NotNull VirtualFile dir, @NotNull String packageName) {
        return getInfoForFile(dir).isInProject(dir) && packageName.equals(getPackageName(dir));
      }
    };
  }

  public void onLowMemory() {
    myPackageDirectoryCache.onLowMemory();
  }

  @NotNull
  private RootInfo buildRootInfo(@NotNull Project project) {
    final RootInfo info = new RootInfo();
    ModuleManager moduleManager = ModuleManager.getInstance(project);
    for (final Module module : moduleManager.getModules()) {
      final ModuleRootManager moduleRootManager = ModuleRootManager.getInstance(module);

      for (final VirtualFile contentRoot : moduleRootManager.getContentRoots()) {
        if (!info.contentRootOf.containsKey(contentRoot) && ensureValid(contentRoot, module)) {
          info.contentRootOf.put(contentRoot, module);
        }
      }

      for (ContentEntry contentEntry : moduleRootManager.getContentEntries()) {
        if (!(contentEntry instanceof ContentEntryImpl) || !((ContentEntryImpl)contentEntry).isDisposed()) {
          for (VirtualFile excludeRoot : contentEntry.getExcludeFolderFiles()) {
            if (!ensureValid(excludeRoot, contentEntry)) continue;

            info.excludedFromModule.put(excludeRoot, module);
          }
          List<String> patterns = contentEntry.getExcludePatterns();
          if (!patterns.isEmpty()) {
            FileTypeAssocTable<Boolean> table = new FileTypeAssocTable<>();
            for (String pattern : patterns) {
              table.addAssociation(FileNameMatcherFactory.getInstance().createMatcher(pattern), Boolean.TRUE);
            }
            info.excludeFromContentRootTables.put(contentEntry.getFile(), table);
          }
        }

        // Init module sources
        for (final SourceFolder sourceFolder : contentEntry.getSourceFolders()) {
          final VirtualFile sourceFolderRoot = sourceFolder.getFile();
          if (sourceFolderRoot != null && ensureValid(sourceFolderRoot, sourceFolder)) {
            info.sourceFolders.put(sourceFolderRoot, sourceFolder);
            info.classAndSourceRoots.add(sourceFolderRoot);
            info.sourceRootOf.putValue(sourceFolderRoot, module);
            info.packagePrefix.put(sourceFolderRoot, sourceFolder.getPackagePrefix());
          }
        }
      }

      for (OrderEntry orderEntry : moduleRootManager.getOrderEntries()) {
        if (orderEntry instanceof LibraryOrSdkOrderEntry) {
          final LibraryOrSdkOrderEntry entry = (LibraryOrSdkOrderEntry)orderEntry;
          final VirtualFile[] sourceRoots = entry.getRootFiles(OrderRootType.SOURCES);
          final VirtualFile[] classRoots = entry.getRootFiles(OrderRootType.CLASSES);

          // Init library sources
          for (final VirtualFile sourceRoot : sourceRoots) {
            if (!ensureValid(sourceRoot, entry)) continue;

            info.classAndSourceRoots.add(sourceRoot);
            info.libraryOrSdkSources.add(sourceRoot);
            info.packagePrefix.put(sourceRoot, "");
          }

          // init library classes
          for (final VirtualFile classRoot : classRoots) {
            if (!ensureValid(classRoot, entry)) continue;

            info.classAndSourceRoots.add(classRoot);
            info.libraryOrSdkClasses.add(classRoot);
            info.packagePrefix.put(classRoot, "");
          }

          if (orderEntry instanceof LibraryOrderEntry) {
            Library library = ((LibraryOrderEntry)orderEntry).getLibrary();
            if (library != null) {
              for (VirtualFile root : ((LibraryEx)library).getExcludedRoots()) {
                if (!ensureValid(root, library)) continue;

                info.excludedFromLibraries.putValue(root, library);
              }
              for (VirtualFile root : sourceRoots) {
                if (!ensureValid(root, library)) continue;

                info.sourceOfLibraries.putValue(root, library);
              }
              for (VirtualFile root : classRoots) {
                if (!ensureValid(root, library)) continue;

                info.classOfLibraries.putValue(root, library);
              }
            }
          }
        }
      }
    }

    for (AdditionalLibraryRootsProvider provider : AdditionalLibraryRootsProvider.EP_NAME.getExtensionList()) {
      Collection<SyntheticLibrary> libraries = provider.getAdditionalProjectLibraries(project);
      for (SyntheticLibrary descriptor : libraries) {
        for (VirtualFile sourceRoot : descriptor.getSourceRoots()) {
          if (!ensureValid(sourceRoot, descriptor)) continue;

          info.libraryOrSdkSources.add(sourceRoot);
          info.classAndSourceRoots.add(sourceRoot);
          if (descriptor instanceof JavaSyntheticLibrary) {
            info.packagePrefix.put(sourceRoot, "");
          }
          info.sourceOfLibraries.putValue(sourceRoot, descriptor);
        }
        for (VirtualFile classRoot : descriptor.getBinaryRoots()) {
          if (!ensureValid(classRoot, project)) continue;

          info.libraryOrSdkClasses.add(classRoot);
          info.classAndSourceRoots.add(classRoot);
          if (descriptor instanceof JavaSyntheticLibrary) {
            info.packagePrefix.put(classRoot, "");
          }
          info.classOfLibraries.putValue(classRoot, descriptor);
        }
        for (VirtualFile file : descriptor.getExcludedRoots()) {
          if (!ensureValid(file, project)) continue;
          info.excludedFromLibraries.putValue(file, descriptor);
        }
      }
    }
    for (DirectoryIndexExcludePolicy policy : DirectoryIndexExcludePolicy.EP_NAME.getExtensions(project)) {
      info.excludedFromProject.addAll(ContainerUtil.filter(policy.getExcludeRootsForProject(), file -> ensureValid(file, policy)));

      Function<Sdk, List<VirtualFile>> fun = policy.getExcludeSdkRootsStrategy();

      if (fun != null) {
        Set<Sdk> sdks = new HashSet<>();

        for (Module m : ModuleManager.getInstance(myProject).getModules()) {
          Sdk sdk = ModuleRootManager.getInstance(m).getSdk();
          if (sdk != null) {
            sdks.add(sdk);
          }
        }

        Set<VirtualFile> roots = new HashSet<>();

        for (Sdk sdk: sdks) {
          roots.addAll(Arrays.asList(sdk.getRootProvider().getFiles(OrderRootType.CLASSES)));
        }

        for (Sdk sdk: sdks) {
          info.excludedFromSdkRoots
            .addAll(ContainerUtil.filter(fun.fun(sdk), file -> ensureValid(file, policy) && !roots.contains(file)));
        }
      }
    }
    for (UnloadedModuleDescription description : moduleManager.getUnloadedModuleDescriptions()) {
      for (VirtualFilePointer pointer : description.getContentRoots()) {
        VirtualFile contentRoot = pointer.getFile();
        if (contentRoot != null && ensureValid(contentRoot, description)) {
          info.contentRootOfUnloaded.put(contentRoot, description.getName());
        }
      }
    }
    return info;
  }

  private static boolean ensureValid(@NotNull VirtualFile file, @NotNull Object container) {
    if (!(file instanceof VirtualFileWithId)) {
      //skip roots from unsupported file systems (e.g. http)
      return false;
    }
    if (!file.isValid()) {
      LOG.error("Invalid root " + file + " in " + container);
      return false;
    }
    return true;
  }

  @NotNull
  private synchronized OrderEntryGraph getOrderEntryGraph() {
    if (myOrderEntryGraph == null) {
      RootInfo rootInfo = buildRootInfo(myProject);
      myOrderEntryGraph = new OrderEntryGraph(myProject, rootInfo);
    }
    return myOrderEntryGraph;
  }

  /**
   * A reverse dependency graph of (library, jdk, module, module source) -> (module).
   * <p>
   * <p>Each edge carries with it the associated OrderEntry that caused the dependency.
   */
  private static class OrderEntryGraph {
    private static class Edge {
      Module myKey;
      ModuleOrderEntry myOrderEntry; // Order entry from myKey -> the node containing the edge
      boolean myRecursive; // Whether this edge should be descended into during graph walk

      Edge(@NotNull Module key, @NotNull ModuleOrderEntry orderEntry, boolean recursive) {
        myKey = key;
        myOrderEntry = orderEntry;
        myRecursive = recursive;
      }

      @Override
      public String toString() {
        return myOrderEntry.toString();
      }
    }

    private static class Node {
      Module myKey;
      List<Edge> myEdges = new ArrayList<>();
      Set<String> myUnloadedDependentModules;

      @Override
      public String toString() {
        return myKey.toString();
      }
    }

    private static class Graph {
      Map<Module, Node> myNodes = new HashMap<>();
    }

    final Project myProject;
    final RootInfo myRootInfo;
    final Set<VirtualFile> myAllRoots;
    Graph myGraph;
    MultiMap<VirtualFile, Node> myRoots; // Map of roots to their root nodes, eg. library jar -> library node
    final SynchronizedSLRUCache<VirtualFile, List<OrderEntry>> myCache;
    final SynchronizedSLRUCache<Module, Set<String>> myDependentUnloadedModulesCache;
    private MultiMap<VirtualFile, OrderEntry> myLibClassRootEntries;
    private MultiMap<VirtualFile, OrderEntry> myLibSourceRootEntries;

    OrderEntryGraph(@NotNull Project project, @NotNull RootInfo rootInfo) {
      myProject = project;
      myRootInfo = rootInfo;
      myAllRoots = myRootInfo.getAllRoots();
      int cacheSize = Math.max(25, (myAllRoots.size() / 100) * 2);
      myCache = new SynchronizedSLRUCache<VirtualFile, List<OrderEntry>>(cacheSize, cacheSize) {
        @NotNull
        @Override
        public List<OrderEntry> createValue(@NotNull VirtualFile key) {
          return collectOrderEntries(key);
        }
      };
      int dependentUnloadedModulesCacheSize = ModuleManager.getInstance(project).getModules().length / 2;
      myDependentUnloadedModulesCache =
        new SynchronizedSLRUCache<Module, Set<String>>(dependentUnloadedModulesCacheSize, dependentUnloadedModulesCacheSize) {
          @NotNull
          @Override
          public Set<String> createValue(@NotNull Module key) {
            return collectDependentUnloadedModules(key);
          }
        };
      initGraph();
      initLibraryRoots();
    }

    private void initGraph() {
      Graph graph = new Graph();

      MultiMap<VirtualFile, Node> roots = MultiMap.createSmart();

      ModuleManager moduleManager = ModuleManager.getInstance(myProject);
      for (final Module module : moduleManager.getModules()) {
        final ModuleRootManager moduleRootManager = ModuleRootManager.getInstance(module);
        List<OrderEnumerationHandler> handlers = OrderEnumeratorBase.getCustomHandlers(module);
        for (OrderEntry orderEntry : moduleRootManager.getOrderEntries()) {
          if (orderEntry instanceof ModuleOrderEntry) {
            ModuleOrderEntry moduleOrderEntry = (ModuleOrderEntry)orderEntry;
            final Module depModule = moduleOrderEntry.getModule();
            if (depModule != null) {
              Node node = graph.myNodes.get(depModule);
              OrderEnumerator en = OrderEnumerator.orderEntries(depModule).exportedOnly();
              if (node == null) {
                node = new Node();
                node.myKey = depModule;
                graph.myNodes.put(depModule, node);

                VirtualFile[] importedClassRoots = en.classes().usingCache().getRoots();
                for (VirtualFile importedClassRoot : importedClassRoots) {
                  roots.putValue(importedClassRoot, node);
                }

                VirtualFile[] importedSourceRoots = en.sources().usingCache().getRoots();
                for (VirtualFile sourceRoot : importedSourceRoots) {
                  roots.putValue(sourceRoot, node);
                }
              }
              boolean shouldRecurse = en.recursively().shouldRecurse(moduleOrderEntry, handlers);
              node.myEdges.add(new Edge(module, moduleOrderEntry, shouldRecurse));
            }
          }
        }
      }
      for (UnloadedModuleDescription description : moduleManager.getUnloadedModuleDescriptions()) {
        for (String depName : description.getDependencyModuleNames()) {
          Module depModule = moduleManager.findModuleByName(depName);
          if (depModule != null) {
            Node node = graph.myNodes.get(depModule);
            if (node == null) {
              node = new Node();
              node.myKey = depModule;
              graph.myNodes.put(depModule, node);
            }
            if (node.myUnloadedDependentModules == null) {
              node.myUnloadedDependentModules = new LinkedHashSet<>();
            }
            node.myUnloadedDependentModules.add(description.getName());
          }
        }
      }

      myGraph = graph;
      myRoots = roots;
    }

    private void initLibraryRoots() {
      MultiMap<VirtualFile, OrderEntry> libClassRootEntries = MultiMap.createSmart();
      MultiMap<VirtualFile, OrderEntry> libSourceRootEntries = MultiMap.createSmart();

      for (final Module module : ModuleManager.getInstance(myProject).getModules()) {
        final ModuleRootManager moduleRootManager = ModuleRootManager.getInstance(module);
        for (OrderEntry orderEntry : moduleRootManager.getOrderEntries()) {
          if (orderEntry instanceof LibraryOrSdkOrderEntry) {
            final LibraryOrSdkOrderEntry entry = (LibraryOrSdkOrderEntry)orderEntry;
            for (final VirtualFile sourceRoot : entry.getRootFiles(OrderRootType.SOURCES)) {
              libSourceRootEntries.putValue(sourceRoot, orderEntry);
            }
            for (final VirtualFile classRoot : entry.getRootFiles(OrderRootType.CLASSES)) {
              libClassRootEntries.putValue(classRoot, orderEntry);
            }
          }
        }
      }

      myLibClassRootEntries = libClassRootEntries;
      myLibSourceRootEntries = libSourceRootEntries;
    }

    @NotNull
    private List<OrderEntry> getOrderEntries(@NotNull VirtualFile file) {
      return myCache.get(file);
    }

    /**
     * Traverses the graph from the given file, collecting all encountered order entries.
     */
    @NotNull
    private List<OrderEntry> collectOrderEntries(@NotNull VirtualFile file) {
      List<VirtualFile> roots = getHierarchy(file, myAllRoots, myRootInfo);
      if (roots == null) {
        return Collections.emptyList();
      }
      Stack<Node> stack = new Stack<>();
      for (VirtualFile root : roots) {
        Collection<Node> nodes = myRoots.get(root);
        for (Node node : nodes) {
          stack.push(node);
        }
      }

      Set<Node> seen = new HashSet<>();
      List<OrderEntry> result = new ArrayList<>();
      while (!stack.isEmpty()) {
        Node node = stack.pop();
        if (seen.contains(node)) {
          continue;
        }
        seen.add(node);

        for (Edge edge : node.myEdges) {
          result.add(edge.myOrderEntry);

          if (edge.myRecursive) {
            Node targetNode = myGraph.myNodes.get(edge.myKey);
            if (targetNode != null) {
              stack.push(targetNode);
            }
          }
        }
      }

      @Nullable Pair<VirtualFile, Collection<Object>> libraryClassRootInfo = myRootInfo.findLibraryRootInfo(roots, false);
      @Nullable Pair<VirtualFile, Collection<Object>> librarySourceRootInfo = myRootInfo.findLibraryRootInfo(roots, true);
      result.addAll(myRootInfo.getLibraryOrderEntries(roots,
                                                      Pair.getFirst(libraryClassRootInfo),
                                                      Pair.getFirst(librarySourceRootInfo),
                                                      myLibClassRootEntries, myLibSourceRootEntries));

      VirtualFile moduleContentRoot = myRootInfo.findNearestContentRoot(roots);
      if (moduleContentRoot != null) {
        ContainerUtil.addIfNotNull(result, myRootInfo.getModuleSourceEntry(roots, moduleContentRoot, myLibClassRootEntries));
      }
      Collections.sort(result, BY_OWNER_MODULE);
      return result;
    }

    @NotNull
    Set<String> getDependentUnloadedModules(@NotNull Module module) {
      return myDependentUnloadedModulesCache.get(module);
    }

    /**
     * @return names of unloaded modules which directly or transitively via exported dependencies depend on the specified module
     */
    @NotNull
    private Set<String> collectDependentUnloadedModules(@NotNull Module module) {
      Node start = myGraph.myNodes.get(module);
      if (start == null) return Collections.emptySet();
      Deque<Node> stack = new ArrayDeque<>();
      stack.push(start);
      Set<Node> seen = new HashSet<>();
      Set<String> result = null;
      while (!stack.isEmpty()) {
        Node node = stack.pop();
        if (!seen.add(node)) {
          continue;
        }
        if (node.myUnloadedDependentModules != null) {
          if (result == null) {
            result = new LinkedHashSet<>(node.myUnloadedDependentModules);
          }
          else {
            result.addAll(node.myUnloadedDependentModules);
          }
        }
        for (Edge edge : node.myEdges) {
          if (edge.myRecursive) {
            Node targetNode = myGraph.myNodes.get(edge.myKey);
            if (targetNode != null) {
              stack.push(targetNode);
            }
          }
        }
      }
      return result != null ? result : Collections.emptySet();
    }
  }


  @NotNull
  public DirectoryInfo getInfoForFile(@NotNull VirtualFile file) {
    if (!file.isValid() || !(file instanceof VirtualFileWithId)) {
      return NonProjectDirectoryInfo.INVALID;
    }

    for (VirtualFile each = file; each != null; each = each.getParent()) {
      int id = ((VirtualFileWithId)each).getId();
      if (!myNonInterestingIds.get(id)) {
        DirectoryInfo info = myRootInfos.get(each);
        if (info != null) {
          return info;
        }

        if (ourFileTypes.isFileIgnored(each)) {
          return NonProjectDirectoryInfo.IGNORED;
        }
        myNonInterestingIds.set(id);
      }
    }

    return NonProjectDirectoryInfo.NOT_UNDER_PROJECT_ROOTS;
  }

  @NotNull
  public Query<VirtualFile> getDirectoriesByPackageName(@NotNull final String packageName, final boolean includeLibrarySources) {
    // Note that this method is used in upsource as well, hence, don't reduce this method's visibility.
    List<VirtualFile> result = myPackageDirectoryCache.getDirectoriesByPackageName(packageName);
    if (!includeLibrarySources) {
      result = ContainerUtil.filter(result, file -> {
        DirectoryInfo info = getInfoForFile(file);
        return info.isInProject(file) && (!info.isInLibrarySource(file) || info.isInModuleSource(file) || info.hasLibraryClassRoot());
      });
    }
    return new CollectionQuery<>(result);
  }

  @Nullable
  public String getPackageName(@NotNull final VirtualFile dir) {
    if (dir.isDirectory()) {
      if (ourFileTypes.isFileIgnored(dir)) {
        return null;
      }

      if (myPackagePrefixByRoot.containsKey(dir)) {
        return myPackagePrefixByRoot.get(dir);
      }

      final VirtualFile parent = dir.getParent();
      if (parent != null) {
        return getPackageNameForSubdir(getPackageName(parent), dir.getName());
      }
    }

    return null;
  }

  @Nullable
  private static String getPackageNameForSubdir(@Nullable String parentPackageName, @NotNull String subdirName) {
    if (parentPackageName == null) return null;
    return parentPackageName.isEmpty() ? subdirName : parentPackageName + "." + subdirName;
  }

  boolean resetOnEvents(@NotNull List<? extends VFileEvent> events) {
    for (VFileEvent event : events) {
      VirtualFile file = event.getFile();
      if (file == null || file.isDirectory()) {
        return true;
      }
    }
    return false;
  }

  @Nullable("returns null only if dir is under ignored folder")
  private static List<VirtualFile> getHierarchy(VirtualFile dir, @NotNull Set<VirtualFile> allRoots, @NotNull RootInfo info) {
    List<VirtualFile> hierarchy = ContainerUtil.newArrayList();
    boolean hasContentRoots = false;
    while (dir != null) {
      hasContentRoots |= info.contentRootOf.get(dir) != null;
      if (!hasContentRoots && ourFileTypes.isFileIgnored(dir)) {
        return null;
      }
      if (allRoots.contains(dir)) {
        hierarchy.add(dir);
      }
      dir = dir.getParent();
    }
    return hierarchy;
  }

  private static class RootInfo {
    // getDirectoriesByPackageName used to be in this order, some clients might rely on that
    @NotNull final LinkedHashSet<VirtualFile> classAndSourceRoots = ContainerUtil.newLinkedHashSet();

    @NotNull final Set<VirtualFile> libraryOrSdkSources = ContainerUtil.newHashSet();
    @NotNull final Set<VirtualFile> libraryOrSdkClasses = ContainerUtil.newHashSet();
    @NotNull final Map<VirtualFile, Module> contentRootOf = ContainerUtil.newHashMap();
    @NotNull final Map<VirtualFile, String> contentRootOfUnloaded = ContainerUtil.newHashMap();
    @NotNull final MultiMap<VirtualFile, Module> sourceRootOf = MultiMap.createSet();
    @NotNull final Map<VirtualFile, SourceFolder> sourceFolders = ContainerUtil.newHashMap();
    @NotNull final MultiMap<VirtualFile, /*Library|SyntheticLibrary*/ Object> excludedFromLibraries = MultiMap.createSmart();
    @NotNull final MultiMap<VirtualFile, /*Library|SyntheticLibrary*/ Object> classOfLibraries = MultiMap.createSmart();
    @NotNull final MultiMap<VirtualFile, /*Library|SyntheticLibrary*/ Object> sourceOfLibraries = MultiMap.createSmart();
    @NotNull final Set<VirtualFile> excludedFromProject = ContainerUtil.newHashSet();
    @NotNull final Set<VirtualFile> excludedFromSdkRoots = ContainerUtil.newHashSet();
    @NotNull final Map<VirtualFile, Module> excludedFromModule = ContainerUtil.newHashMap();
    @NotNull final Map<VirtualFile, FileTypeAssocTable<Boolean>> excludeFromContentRootTables = ContainerUtil.newHashMap();
    @NotNull final Map<VirtualFile, String> packagePrefix = ContainerUtil.newHashMap();

    @NotNull
    Set<VirtualFile> getAllRoots() {
      LinkedHashSet<VirtualFile> result = ContainerUtil.newLinkedHashSet();
      result.addAll(classAndSourceRoots);
      result.addAll(contentRootOf.keySet());
      result.addAll(contentRootOfUnloaded.keySet());
      result.addAll(excludedFromLibraries.keySet());
      result.addAll(excludedFromModule.keySet());
      result.addAll(excludedFromProject);
      result.addAll(excludedFromSdkRoots);
      return result;
    }

    /**
     * Returns nearest content root for a file by its parent directories hierarchy. If the file is excluded (i.e. located under an excluded
     * root and there are no source roots on the path to the excluded root) returns {@code null}.
     */
    @Nullable
    private VirtualFile findNearestContentRoot(@NotNull List<VirtualFile> hierarchy) {
      Collection<Module> sourceRootOwners = null;
      boolean underExcludedSourceRoot = false;
      for (VirtualFile root : hierarchy) {
        Module module = contentRootOf.get(root);
        Module excludedFrom = excludedFromModule.get(root);
        if (module != null) {
          FileTypeAssocTable<Boolean> table = excludeFromContentRootTables.get(root);
          if (table != null && isExcludedByPattern(root, hierarchy, table)) {
            excludedFrom = module;
          }
        }
        if (module != null && (excludedFrom != module || underExcludedSourceRoot && sourceRootOwners.contains(module))) {
          return root;
        }
        if (excludedFrom != null || excludedFromProject.contains(root) || contentRootOfUnloaded.containsKey(root)) {
          if (sourceRootOwners != null) {
            underExcludedSourceRoot = true;
          }
          else {
            return null;
          }
        }

        if (!underExcludedSourceRoot && sourceRootOf.containsKey(root)) {
          Collection<Module> modulesForSourceRoot = sourceRootOf.get(root);
          if (!modulesForSourceRoot.isEmpty()) {
            if (sourceRootOwners == null) {
              sourceRootOwners = modulesForSourceRoot;
            }
            else {
              sourceRootOwners = ContainerUtil.union(sourceRootOwners, modulesForSourceRoot);
            }
          }
        }
      }
      return null;
    }

    private static boolean isExcludedByPattern(VirtualFile contentRoot, List<VirtualFile> hierarchy, FileTypeAssocTable<Boolean> table) {
      for (VirtualFile file : hierarchy) {
        if (table.findAssociatedFileType(file.getNameSequence()) != null) {
          return true;
        }
        if (file.equals(contentRoot)) {
          break;
        }
      }
      return false;
    }

    @Nullable
    private VirtualFile findNearestContentRootForExcluded(@NotNull List<VirtualFile> hierarchy) {
      for (VirtualFile root : hierarchy) {
        if (contentRootOf.containsKey(root) || contentRootOfUnloaded.containsKey(root)) {
          return root;
        }
      }
      return null;
    }

    /**
     * @return root and set of libraries that provided it
     */
    @Nullable
    private Pair<VirtualFile, Collection<Object>> findLibraryRootInfo(@NotNull List<VirtualFile> hierarchy, boolean source) {
      Set<Object> librariesToIgnore = ContainerUtil.newHashSet();
      for (VirtualFile root : hierarchy) {
        librariesToIgnore.addAll(excludedFromLibraries.get(root));
        if (source && libraryOrSdkSources.contains(root)) {
          if (!sourceOfLibraries.containsKey(root)) {
            return Pair.create(root, Collections.emptySet());
          }
          Collection<Object> rootProducers = findLibraryRootProducers(sourceOfLibraries.get(root), root, librariesToIgnore);
          if (!rootProducers.isEmpty()) {
            return Pair.create(root, rootProducers);
          }
        }
        else if (!source && libraryOrSdkClasses.contains(root)) {
          if (!classOfLibraries.containsKey(root)) {
            return Pair.create(root, Collections.emptySet());
          }
          Collection<Object> rootProducers = findLibraryRootProducers(classOfLibraries.get(root), root, librariesToIgnore);
          if (!rootProducers.isEmpty()) {
            return Pair.create(root, rootProducers);
          }
        }
      }
      return null;
    }

    @NotNull
    private static Collection<Object> findLibraryRootProducers(@NotNull Collection<Object> producers,
                                                               @NotNull VirtualFile root,
                                                               @NotNull Set<Object> librariesToIgnore) {
      Set<Object> libraries = ContainerUtil.newHashSet();
      for (Object library : producers) {
        if (librariesToIgnore.contains(library)) continue;
        if (library instanceof SyntheticLibrary) {
          Condition<VirtualFile> exclusion = ((SyntheticLibrary)library).getExcludeFileCondition();
          if (exclusion != null && exclusion.value(root)) {
            continue;
          }
        }
        libraries.add(library);
      }
      return libraries;
    }

    private String calcPackagePrefix(@NotNull VirtualFile root,
                                     @NotNull List<VirtualFile> hierarchy,
                                     VirtualFile moduleContentRoot,
                                     VirtualFile libraryClassRoot,
                                     VirtualFile librarySourceRoot) {
      VirtualFile packageRoot = findPackageRootInfo(hierarchy, moduleContentRoot, libraryClassRoot, librarySourceRoot);
      String prefix = packagePrefix.get(packageRoot);
      if (prefix != null && !root.equals(packageRoot)) {
        assert packageRoot != null;
        String relative = VfsUtilCore.getRelativePath(root, packageRoot, '.');
        prefix = StringUtil.isEmpty(prefix) ? relative : prefix + '.' + relative;
      }
      return prefix;
    }

    @Nullable
    private VirtualFile findPackageRootInfo(@NotNull List<VirtualFile> hierarchy,
                                            VirtualFile moduleContentRoot,
                                            VirtualFile libraryClassRoot,
                                            VirtualFile librarySourceRoot) {
      for (VirtualFile root : hierarchy) {
        if (moduleContentRoot != null &&
            sourceRootOf.get(root).contains(contentRootOf.get(moduleContentRoot)) &&
            librarySourceRoot == null) {
          return root;
        }
        if (root.equals(libraryClassRoot) || root.equals(librarySourceRoot)) {
          return root;
        }
        if (root.equals(moduleContentRoot) && !sourceRootOf.containsKey(root) && librarySourceRoot == null && libraryClassRoot == null) {
          return null;
        }
      }
      return null;
    }

    @NotNull
    private LinkedHashSet<OrderEntry> getLibraryOrderEntries(@NotNull List<VirtualFile> hierarchy,
                                                             @Nullable VirtualFile libraryClassRoot,
                                                             @Nullable VirtualFile librarySourceRoot,
                                                             @NotNull MultiMap<VirtualFile, OrderEntry> libClassRootEntries,
                                                             @NotNull MultiMap<VirtualFile, OrderEntry> libSourceRootEntries) {
      LinkedHashSet<OrderEntry> orderEntries = ContainerUtil.newLinkedHashSet();
      for (VirtualFile root : hierarchy) {
        if (root.equals(libraryClassRoot) && !sourceRootOf.containsKey(root)) {
          orderEntries.addAll(libClassRootEntries.get(root));
        }
        if (root.equals(librarySourceRoot) && libraryClassRoot == null) {
          orderEntries.addAll(libSourceRootEntries.get(root));
        }
        if (libClassRootEntries.containsKey(root) || sourceRootOf.containsKey(root) && librarySourceRoot == null) {
          break;
        }
      }
      return orderEntries;
    }


    @Nullable
    private ModuleSourceOrderEntry getModuleSourceEntry(@NotNull List<VirtualFile> hierarchy,
                                                        @NotNull VirtualFile moduleContentRoot,
                                                        @NotNull MultiMap<VirtualFile, OrderEntry> libClassRootEntries) {
      Module module = contentRootOf.get(moduleContentRoot);
      for (VirtualFile root : hierarchy) {
        if (sourceRootOf.get(root).contains(module)) {
          return ContainerUtil.findInstance(ModuleRootManager.getInstance(module).getOrderEntries(), ModuleSourceOrderEntry.class);
        }
        if (libClassRootEntries.containsKey(root)) {
          return null;
        }
      }
      return null;
    }
  }

  @NotNull
  private static Pair<DirectoryInfo, String> calcDirectoryInfo(@NotNull final VirtualFile root,
                                                               @NotNull final List<VirtualFile> hierarchy,
                                                               @NotNull RootInfo info) {
    VirtualFile moduleContentRoot = info.findNearestContentRoot(hierarchy);
    Pair<VirtualFile, Collection<Object>> librarySourceRootInfo = info.findLibraryRootInfo(hierarchy, true);
    VirtualFile librarySourceRoot = Pair.getFirst(librarySourceRootInfo);

    Pair<VirtualFile, Collection<Object>> libraryClassRootInfo = info.findLibraryRootInfo(hierarchy, false);
    VirtualFile libraryClassRoot = Pair.getFirst(libraryClassRootInfo);

    boolean inProject = moduleContentRoot != null ||
                        (libraryClassRoot != null || librarySourceRoot != null) && !info.excludedFromSdkRoots.contains(root);

    VirtualFile nearestContentRoot;
    if (inProject) {
      nearestContentRoot = moduleContentRoot;
    }
    else {
      nearestContentRoot = info.findNearestContentRootForExcluded(hierarchy);
      if (nearestContentRoot == null) {
        return new Pair<>(NonProjectDirectoryInfo.EXCLUDED, null);
      }
    }

    VirtualFile sourceRoot = info.findPackageRootInfo(hierarchy, moduleContentRoot, null, librarySourceRoot);

    VirtualFile moduleSourceRoot = info.findPackageRootInfo(hierarchy, moduleContentRoot, null, null);
    boolean inModuleSources = moduleSourceRoot != null;
    boolean inLibrarySource = librarySourceRoot != null;
    SourceFolder sourceFolder = moduleSourceRoot != null ? info.sourceFolders.get(moduleSourceRoot) : null;

    Module module = info.contentRootOf.get(nearestContentRoot);
    String unloadedModuleName = info.contentRootOfUnloaded.get(nearestContentRoot);
    FileTypeAssocTable<Boolean> contentExcludePatterns =
      moduleContentRoot != null ? info.excludeFromContentRootTables.get(moduleContentRoot) : null;
    Condition<VirtualFile> libraryExclusionPredicate = getLibraryExclusionPredicate(librarySourceRootInfo);

    DirectoryInfo directoryInfo = contentExcludePatterns != null || libraryExclusionPredicate != null
                                  ? new DirectoryInfoWithExcludePatterns(root, module, nearestContentRoot, sourceRoot, sourceFolder,
                                                                         libraryClassRoot, inModuleSources, inLibrarySource, !inProject,
                                                                         contentExcludePatterns, libraryExclusionPredicate, unloadedModuleName)
                                  : new DirectoryInfoImpl(root, module, nearestContentRoot, sourceRoot, sourceFolder,
                                                          libraryClassRoot, inModuleSources, inLibrarySource,
                                                          !inProject, unloadedModuleName);

    String packagePrefix = info.calcPackagePrefix(root, hierarchy, moduleContentRoot, libraryClassRoot, librarySourceRoot);

    return Pair.create(directoryInfo, packagePrefix);
  }

  @Nullable
  private static Condition<VirtualFile> getLibraryExclusionPredicate(@Nullable Pair<VirtualFile, Collection<Object>> libraryRootInfo) {
    Condition<VirtualFile> result = Conditions.alwaysFalse();
    if (libraryRootInfo != null) {
      for (Object library : libraryRootInfo.second) {
        Condition<VirtualFile> exclusionPredicate =
          library instanceof SyntheticLibrary ? ((SyntheticLibrary)library).getExcludeFileCondition() : null;
        if (exclusionPredicate == null) continue;
        result = Conditions.or(result, exclusionPredicate);
      }
    }
    return result != Condition.FALSE ? result : null;
  }

  @NotNull
  public List<OrderEntry> getOrderEntries(@NotNull DirectoryInfo info) {
    if (!(info instanceof DirectoryInfoImpl)) return Collections.emptyList();
    return getOrderEntryGraph().getOrderEntries(((DirectoryInfoImpl)info).getRoot());
  }

  @NotNull
  Set<String> getDependentUnloadedModules(@NotNull Module module) {
    return getOrderEntryGraph().getDependentUnloadedModules(module);
  }

  /**
   * An LRU cache with synchronization around the primary cache operations (get() and insertion
   * of a newly created value). Other map operations are not synchronized.
   */
  abstract static class SynchronizedSLRUCache<K, V> extends SLRUMap<K, V> {
    protected final Object myLock = new Object();

    SynchronizedSLRUCache(final int protectedQueueSize, final int probationalQueueSize) {
      super(protectedQueueSize, probationalQueueSize);
    }

    @NotNull
    public abstract V createValue(@NotNull K key);

    @Override
    @NotNull
    public V get(K key) {
      V value;
      synchronized (myLock) {
        value = super.get(key);
        if (value != null) {
          return value;
        }
      }
      value = createValue(key);
      synchronized (myLock) {
        put(key, value);
      }
      return value;
    }
  }
}
