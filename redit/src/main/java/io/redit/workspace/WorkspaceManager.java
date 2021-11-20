/*
 * MIT License
 *
 * Copyright (c) 2021 SATE-Lab
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.redit.workspace;

import io.redit.Constants;
import io.redit.util.FileUtil;
import io.redit.util.ZipUtil;
import io.redit.dsl.entities.Deployment;
import io.redit.dsl.entities.Node;
import io.redit.dsl.entities.PathEntry;
import io.redit.dsl.entities.Service;
import io.redit.exceptions.WorkspaceException;
import net.lingala.zip4j.exception.ZipException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

public class WorkspaceManager {
    private final static Logger logger = LoggerFactory.getLogger(WorkspaceManager.class);

    private final Deployment deployment;
    private final Path workingDirectory;
    private Map<String, String> fakeTimePathMap;
    private Map<String, Map<String, String>> serviceToMapOfCompressedToDecompressedMap;
    private Map<String, String> sharedDirectoriesMap;

    public WorkspaceManager(Deployment deployment) {
        this(deployment, Constants.DEFAULT_WORKING_DIRECTORY_NAME);
    }

    public WorkspaceManager(Deployment deployment, String topLevelWorkingDirectory) {
        this.deployment = deployment;
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MMM-dd_HH-mm-ss-SSS");
        this.workingDirectory = Paths.get(".", topLevelWorkingDirectory, deployment.getName() + "_" +
                simpleDateFormat.format(new Date())).toAbsolutePath().normalize();
    }

    public Map<String, NodeWorkspace> createWorkspace() throws WorkspaceException {
        Map<String, NodeWorkspace> retMap = new HashMap<>();

        // Creates the working directory
        try {
            logger.info("Creating the working directory at {}", workingDirectory.toString());
            Files.createDirectories(workingDirectory);
        } catch (IOException e) {
            throw new WorkspaceException("Error in creating Redit working directory at " + workingDirectory.toString(), e);
        }

        // Creates the shared directories
        sharedDirectoriesMap = createSharedDirectories();

        // Decompress compressed application paths in services
        serviceToMapOfCompressedToDecompressedMap = decompressCompressedApplicationPaths();

        // Copies over libfaketime binaries to the working directory
        fakeTimePathMap = copyOverLibFakeTime(workingDirectory);

        // Creates the nodes' workspaces
        for (Node node: deployment.getNodes().values()) {
            retMap.put(node.getName(), createNodeWorkspace(node));
        }

        return Collections.unmodifiableMap(retMap);
    }

    /**
     * This method replaces slashes in a path without slashes to be used as file or directory name
     * @param path to replace slashes in
     * @return the given path with replaced slashes
     */
    private String pathToStringWithoutSlashes(String path) {
        return path.replaceAll("[\\\\/]", "_-");
    }

    private Map<String, String> createSharedDirectories() throws WorkspaceException {
        Map<String, String> sharedDirectoriesMap = new HashMap<>();

        // Creating the shared directories
        Path sharedDirectoriesRoot = workingDirectory.resolve(Constants.SHAERD_DIRECTORIES_ROOT_NAME);
        try {
            Files.createDirectory(sharedDirectoriesRoot);
        } catch (IOException e) {
            throw new WorkspaceException("Error in creating shared directories root directory", e);
        }

        for (String path: deployment.getSharedDirectories()) {

            // Creating the shared directories
            try {
                Path sharedDirectory = sharedDirectoriesRoot.resolve(pathToStringWithoutSlashes(path));
                Files.createDirectory(sharedDirectory);
                sharedDirectoriesMap.put(sharedDirectory.toString(), path);
            } catch (IOException e) {
                throw new WorkspaceException("Error in creating shared directory " + path, e);
            }
        }

        return sharedDirectoriesMap;
    }

    private Map<String, Map<String, String>> decompressCompressedApplicationPaths() throws WorkspaceException {
        Map<String, Map<String, String>> retMap = new HashMap<>();
        Path decompressedDirectory = workingDirectory.resolve(Constants.DECOMPRESSED_DIRECTORIES_ROOT_NAME);

        try {
            Files.createDirectory(decompressedDirectory);
        } catch (IOException e) {
            throw new WorkspaceException("Error in creating decompressed directory at " + decompressedDirectory, e);
        }

        for (Service service: deployment.getServices().values()) {
            retMap.put(service.getName(), new HashMap<>());
            for (PathEntry pathEntry: service.getApplicationPaths().values()) {
                if (pathEntry.shouldBeDecompressed()) {
                    if (pathEntry.getPath().endsWith(".zip")) {
                        File targetDir = decompressedDirectory.resolve(service.getName())
                                .resolve(pathToStringWithoutSlashes(pathEntry.getPath())).toFile();

                        try {
                            ZipUtil.unzip(pathEntry.getPath(), targetDir.toString());
                        } catch (InterruptedException | IOException | ZipException e) {
                            throw new WorkspaceException("Error while unzipping " + pathEntry.getPath(), e);
                        }
                        retMap.get(service.getName()).put(pathEntry.getPath(), targetDir.toString());

                    }else{

                        if (!pathEntry.getPath().endsWith(".tar.gz")) {
                            throw new WorkspaceException("Decompression is only supported for zip, tar.gz files!"
                                + pathEntry.getPath() + " is not a supported file.");
                        }

                        File targetDir = decompressedDirectory.resolve(service.getName())
                                .resolve(pathToStringWithoutSlashes(pathEntry.getPath())).toFile();

                        try {
                            ZipUtil.unTarGzip(pathEntry.getPath(), targetDir.toString());
                        } catch (InterruptedException | IOException  e) {
                            throw new WorkspaceException("Error while unzipping " + pathEntry.getPath(), e);
                        }
                        retMap.get(service.getName()).put(pathEntry.getPath(), targetDir.toString());

                    }
                }
            }
        }
        return retMap;
    }

    // TODO should this be public?
    public NodeWorkspace createNodeWorkspace(Node node)
            throws WorkspaceException {
        logger.info("Creating workspace for node {}", node.getName());

        Map<String, String> compressedToDecompressedMap = serviceToMapOfCompressedToDecompressedMap.get(node.getServiceName());

        // Creates the node's working directory
        Path nodeWorkingDirectory = workingDirectory.resolve(node.getName());
        try {
            Files.createDirectory(nodeWorkingDirectory);
        } catch (IOException e) {
            throw new WorkspaceException("Error in creating Redit node working directory \""
                    + node.getName() + "\"!", e);
        }

        // Creates the node root directory
        Path nodeRootDirectory = nodeWorkingDirectory.resolve(Constants.NODE_ROOT_DIRECTORY_NAME);
        try {
            Files.createDirectory(nodeRootDirectory);
        } catch (IOException e) {
            throw new WorkspaceException("Error in creating Redit node root directory \""
                    + node.getName() + "\"!", e);
        }

        // Creates the node's log directory
        Path nodeLogDirectory = nodeWorkingDirectory.resolve(Constants.NODE_LOG_DIRECTORY_NAME);
        try {
            Files.createDirectory(nodeLogDirectory);
        } catch (IOException e) {
            throw new WorkspaceException("Error in creating Redit node log directory \""
                    + node.getName() + "\"!", e);
        }

        // Creates the node's log files
        Map<String, String> logFilesMap = createLogFiles(node, nodeLogDirectory);

        // Creates the node's log directories
        Map<String, String> logDirectoriesMap = createLogDirectories(node, nodeLogDirectory);

        Service nodeService = deployment.getService(node.getServiceName());

        // Copies over the node paths to the node root directory
        List<NodeWorkspace.PathMappingEntry> pathMappingList = copyOverNodePathsAndMakePathMappingList(node, nodeService,
                nodeRootDirectory, compressedToDecompressedMap);

        // Adds fakeTimeLib paths to the path mapping
        fakeTimePathMap.entrySet().stream().forEach(e -> pathMappingList.add(
                new NodeWorkspace.PathMappingEntry(e.getKey(), e.getValue(), true)));

        // Determines the instrumentable paths
        Set<String> instrumentablePaths = new HashSet<>();
        for (String instrumentablePath: nodeService.getInstrumentablePaths()) {
            instrumentablePaths.add(getLocalPathFromNodeTargetPath(pathMappingList, instrumentablePath, true));
        }

        // Creates the node workspace object
        return new NodeWorkspace(
                instrumentablePaths,
                getNodeLibPaths(nodeService, pathMappingList),
                nodeWorkingDirectory.toString(),
                nodeRootDirectory.toString(),
                nodeLogDirectory.toString(),
                logDirectoriesMap,
                logFilesMap,
                sharedDirectoriesMap,
                pathMappingList);
    }

    private Map<String, String> createLogFiles(Node node, Path nodeLogDirectory) throws WorkspaceException {
        Map<String, String> logFilesMap = new HashMap<>();

        for (String path: getNodeLogFiles(node)) {
            Path logFile = nodeLogDirectory.resolve(pathToStringWithoutSlashes(path));
            try {
                logFile.toFile().createNewFile();
                logFilesMap.put(logFile.toString(), path);
            } catch (IOException e) {
                throw new WorkspaceException("Error while creating log file " + logFile, e);
            }
        }

        return logFilesMap;
    }

    private Map<String, String> createLogDirectories(Node node, Path nodeLogDirectory) throws WorkspaceException {
        Map<String, String> logDirectoriesMap = new HashMap<>();

        for (String path: getNodeLogDirectories(node)) {
            Path logDirectory = nodeLogDirectory.resolve(pathToStringWithoutSlashes(path));
            try {
                Files.createDirectory(logDirectory);
                logDirectoriesMap.put(logDirectory.toString(), path);
            } catch (IOException e) {
                throw new WorkspaceException("Error while creating log directory " + logDirectory, e);
            }
        }

        return logDirectoriesMap;
    }

    protected Set<String> getNodeLogFiles(Node node) {
        Set<String> logFiles = new HashSet<>(deployment.getService(node.getServiceName()).getLogFiles());
        logFiles.addAll(node.getLogFiles());
        return logFiles;
    }

    protected Set<String> getNodeLogDirectories(Node node) {
        Set<String> logDirectories = new HashSet<>(deployment.getService(node.getServiceName()).getLogDirectories());
        logDirectories.addAll(node.getLogDirectories());
        return logDirectories;
    }

    private Set<String> getNodeLibPaths(Service nodeService, List<NodeWorkspace.PathMappingEntry> pathMappingList) throws WorkspaceException {
        Set<String> libPaths = new HashSet<>();

        // Adds application paths that are library to the set
        for (PathEntry pathEntry : nodeService.getApplicationPaths().values()) {
            if (pathEntry.isLibrary()) {
                String localLibPath = getLocalPathFromNodeTargetPath(pathMappingList, pathEntry.getTargetPath(),
                        false);
                if (localLibPath != null) {
                    try {
                        libPaths.addAll(FileUtil.findAllMatchingPaths(localLibPath));
                    } catch (IOException e) {
                        throw new WorkspaceException("Error while trying to expand lib path " + pathEntry.getTargetPath(), e);
                    }
                }
            }
        }

        // Adds marked library paths
        for (String libPath : nodeService.getLibraryPaths()) {
            String localLibPath = getLocalPathFromNodeTargetPath(pathMappingList, libPath, false);
            if (localLibPath != null) {
                try {
                    libPaths.addAll(FileUtil.findAllMatchingPaths(localLibPath));
                } catch (IOException e) {
                    throw new WorkspaceException("Error while trying to expand lib path " + libPath, e);
                }
            }
        }

        return libPaths;
    }

    private String getLocalPathFromNodeTargetPath(List<NodeWorkspace.PathMappingEntry> pathMappingList, String path,
                                                  Boolean mustBeWritable) {
        for (int i=pathMappingList.size()-1; i>=0; i--) {
            NodeWorkspace.PathMappingEntry entry = pathMappingList.get(i);
            if (path.startsWith(entry.getDestination())) {
                if (!mustBeWritable || !entry.isReadOnly()) {
                    return path.replaceFirst(entry.getDestination(), entry.getSource());
                }
            }
        }

        return null;
    }

    private List<NodeWorkspace.PathMappingEntry> copyOverNodePathsAndMakePathMappingList(Node node, Service nodeService,
                     Path nodeRootDirectory, Map<String, String> compressedToDecompressedMap) throws WorkspaceException {
        List<NodeWorkspace.PathMappingEntry> pathMap = new ArrayList<>();
        try {
            // Copies over node's service paths based on their entry path order
            for (PathEntry pathEntry : nodeService.getApplicationPaths().values().stream()
                    .sorted((p1, p2) -> p1.getOrder().compareTo(p2.getOrder()))
                    .collect(Collectors.toList())) {
                Path sourcePath = Paths.get(pathEntry.shouldBeDecompressed()?
                        compressedToDecompressedMap.get(pathEntry.getPath()):pathEntry.getPath());

                if (pathEntry.shouldCopyOverToWorkspace()) {
                    Path destPath = nodeRootDirectory.resolve(pathToStringWithoutSlashes(pathEntry.getPath()));
                    if (sourcePath.toFile().isDirectory()) {
                        FileUtil.copyDirectory(sourcePath, destPath);
                    } else {
                        Files.copy(sourcePath, destPath,
                                StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
                    }
                    pathMap.add(new NodeWorkspace.PathMappingEntry(destPath.toString(), pathEntry.getTargetPath(),
                            false));
                } else {
                    pathMap.add(new NodeWorkspace.PathMappingEntry(sourcePath.toString(), pathEntry.getTargetPath(),
                            true));

                }
            }

            // Copies over node's paths based on their entry path order
            for (PathEntry pathEntry : node.getApplicationPaths().values().stream()
                    .sorted((p1, p2) -> p1.getOrder().compareTo(p2.getOrder()))
                    .collect(Collectors.toList())) {
                if (pathEntry.shouldCopyOverToWorkspace()) {
                    Path sourcePath = Paths.get(pathEntry.getPath());
                    Path destPath = nodeRootDirectory.resolve(pathToStringWithoutSlashes(pathEntry.getPath()));
                    if (sourcePath.toFile().isDirectory()) {
                        FileUtil.copyDirectory(sourcePath, destPath);
                    } else {
                        Files.copy(sourcePath, destPath,
                                StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
                    }
                    pathMap.add(new NodeWorkspace.PathMappingEntry(destPath.toString(), pathEntry.getTargetPath(),
                            false));
                } else {
                    pathMap.add(new NodeWorkspace.PathMappingEntry(pathEntry.getPath(), pathEntry.getTargetPath(),
                            true));
                }
            }

            for (String instrumentablePath: nodeService.getInstrumentablePaths()) {
                // Copies over instrumentable paths if it is not marked as willBeChanged and updates path mapping
                String localInstrumentablePath = getLocalPathFromNodeTargetPath(pathMap,
                        instrumentablePath, true);

                if (localInstrumentablePath == null) {
                    localInstrumentablePath = getLocalPathFromNodeTargetPath(pathMap,
                            instrumentablePath, false);

                    if (localInstrumentablePath == null || !new File(localInstrumentablePath).exists()) {
                        throw new WorkspaceException("The marked instrumentable path `" + nodeService.getInstrumentablePaths() +
                                "` is not marked as willBeChanged or does not exist!");
                    }

                    Path localInstrumentablePathObj = Paths.get(localInstrumentablePath);

                    Path destPath = nodeRootDirectory.resolve("Instrumentable_" + pathToStringWithoutSlashes(
                            instrumentablePath));
                    if (new File(localInstrumentablePath).isDirectory()) {
                        FileUtil.copyDirectory(localInstrumentablePathObj, destPath);
                    } else {
                        Files.copy(localInstrumentablePathObj, destPath,
                                StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
                    }
                    pathMap.add(new NodeWorkspace.PathMappingEntry(destPath.toString(), instrumentablePath,
                            false));
                }
            }

            return pathMap;
        } catch (IOException e) {
            throw new WorkspaceException("Error in copying over node " + node.getName() + " binaries to its workspace!", e);
        }
    }

    private Map<String, String> copyOverLibFakeTime(Path workingDirectory) throws WorkspaceException {

        Map<String,String> pathMap = new HashMap<>();

        // Creates the faketime lib directory
        Path fakeTimeLibDirectory = workingDirectory.resolve(Constants.FAKETIME_DIRECTORY_NAME);
        try {
            Files.createDirectory(fakeTimeLibDirectory);
        } catch (IOException e) {
            throw new WorkspaceException("Error in creating Redit faketime lib directory!", e);
        }

        String[] filesToBeCopied = {Constants.FAKETIME_LIB_FILE_NAME, Constants.FAKETIMEMT_LIB_FILE_NAME};

        try {
            for (String fileToBeCopied: filesToBeCopied) {
                Path fakeTimePath = fakeTimeLibDirectory.resolve(fileToBeCopied);
                Files.copy(Thread.currentThread().getContextClassLoader().getResourceAsStream(fileToBeCopied),
                        fakeTimePath);
                pathMap.put(fakeTimePath.toString(), Constants.FAKETIME_TARGET_BASE_PATH  + fileToBeCopied);
            }
        } catch (IOException e) {
            throw new WorkspaceException("Error in copying over faketime lib binaries to the workspace!", e);
        }

        return pathMap;
    }
}
