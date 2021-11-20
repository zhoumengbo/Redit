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

package io.redit.exceptions;

/**
 * This exception is used when a deployment entity referred in another deployment entity does not exist
 */
public class DeploymentEntityBadReferenceException extends RuntimeException {
    private String mainType;
    private String referencedType;
    private String mainName;
    private String referencedName;


    public DeploymentEntityBadReferenceException(String referencedType, String referencedName, String mainType, String mainName) {
        this.mainType = mainType;
        this.referencedType = referencedType;
        this.mainName = mainName;
        this.referencedName = referencedName;
    }

    @Override
    public String getMessage() {
        return referencedType + " " + referencedName + " referenced in " + mainType + " " + mainName + " definition does not exist!";
    }
}
