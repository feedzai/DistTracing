/*
 * Copyright 2018 Feedzai
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 	http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.feedzai.commons.tracing.engine;

import com.feedzai.commons.tracing.api.Promise;

import java.util.function.Consumer;

public class MockPromise implements Promise<String, MockPromise> {

    Consumer callOnCompletion;
    Consumer callOnError;



    public void complete(){
        callOnCompletion.accept("");
    }

    @Override
    public MockPromise onCompletePromise(Consumer<String> callOnCompletion) {
        this.callOnCompletion = callOnCompletion;
        return this;
    }

    @Override
    public MockPromise onErrorPromise(Consumer<Throwable> callOnError) {
        this.callOnError = callOnError;
        return this;
    }
}
