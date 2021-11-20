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
package io.redit.dsl;

import io.redit.dsl.entities.Deployment;
import io.redit.instrumentation.InstrumentationDefinition;

import java.util.List;

/**
 * Any referable deployment entity that has instrumentation as part of its realization in the run sequence order enforcement
 * needs to implement this interface
 */
public interface Instrumentable {
    /**
     * Given the deployment definition, this method should generate abstract instrumentation definitions for the implementing
     * class
     * @param deployment the deployment definition object
     * @return a list of abstract instrumentation definitions
     */
    public List<InstrumentationDefinition> generateInstrumentationDefinitions(Deployment deployment);
}
