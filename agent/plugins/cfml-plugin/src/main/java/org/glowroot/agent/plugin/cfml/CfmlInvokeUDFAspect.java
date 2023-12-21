/*
 * Copyright 2016-2017 the original author or authors.
 *
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
 */
package org.glowroot.agent.plugin.cfml;

import org.glowroot.agent.plugin.api.*;
import org.glowroot.agent.plugin.api.weaving.*;

public class CfmlInvokeUDFAspect {

    @Pointcut(className = "coldfusion.runtime.CfJspPage", methodName = "_invokeUDF",
            methodParameterTypes = {".."},
            nestingGroup = "cfml",
            timerName = "cfmlinvokeUDF")

    public static class CfmlInvokeUDFAdvice {

        private static final TimerName timerName = Agent.getTimerName(CfmlInvokeUDFAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(ThreadContext context,
                                          @BindReceiver Object methodClass,
                                          @BindParameter Object methodObject,
                                          @BindParameter String methodName) {
            // get filename from classname
            // String filename = HttpCfmlPages.getFilename(methodClass.getClass());
            return context.startTraceEntry(MessageSupplier.create("cfml_invokeUDF: {}", methodName),
                    timerName);
        }

        @OnReturn
        public static void onReturn(@BindTraveler TraceEntry traceEntry) {

            traceEntry.end();
        }

        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                                   @BindTraveler TraceEntry traceEntry) {
            traceEntry.endWithError(t);
        }
    }

}
