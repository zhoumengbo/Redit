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

package io.redit.instrumentation;

import io.redit.dsl.entities.Deployment;
import io.redit.exceptions.InstrumentationException;
import io.redit.workspace.NodeWorkspace;

import java.util.Map;

public interface InstrumentationEngine {
    /**
     * This method should instrument the nodes based on the given definition and node workspaces. It is important that
     * this method doesn't change any of the application paths
     * @param deployment the deployment definition object
     * @param nodeWorkspaceMap the map of node name to the node's workspace information
     * @throws InstrumentationException if something goes wrong during instrumentation
     */
    void instrumentNodes(Deployment deployment, Map<String, NodeWorkspace> nodeWorkspaceMap)
            throws InstrumentationException;

}
