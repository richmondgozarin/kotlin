/*
 * Copyright 2010-2017 JetBrains s.r.o.
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

@file:Suppress("unused")

package kotlin.script.dependencies

import sun.reflect.generics.reflectiveObjects.NotImplementedException
import java.io.File
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

interface ScriptDependenciesResolver {

    enum class ReportSeverity { ERROR, WARNING, INFO, DEBUG }

    @Deprecated(replaceWith = ReplaceWith("resolve"), message = "Replace with the resolve function with callback")
    fun resolve(script: ScriptContents,
                environment: Map<String, Any?>?,
                report: (ReportSeverity, String, ScriptContents.Position?) -> Unit,
                previousDependencies: KotlinScriptExternalDependencies?
    ): Future<KotlinScriptExternalDependencies?> = PseudoFuture(null)

    fun resolve(script: ScriptContents,
                environment: Map<String, Any?>?,
                report: (ReportSeverity, String, ScriptContents.Position?) -> Unit,
                onResult: (ValueOrError<KotlinScriptExternalDependencies?>) -> Unit
    ): ResultOrAsync<KotlinScriptExternalDependencies?> = ResultOrAsync.Result(ValueOrError.Error(NotImplementedException()))
}

class BasicScriptDependenciesResolver : ScriptDependenciesResolver

interface ScriptContents {

    data class Position(val line: Int, val col: Int)

    val file: File?
    val annotations: Iterable<Annotation>
    val text: CharSequence?
}

fun KotlinScriptExternalDependencies?.asFuture(): PseudoFuture<KotlinScriptExternalDependencies?> = PseudoFuture(this)

class PseudoFuture<T>(private val value: T): Future<T> {
    override fun get(): T = value
    override fun get(p0: Long, p1: TimeUnit): T  = value
    override fun cancel(p0: Boolean): Boolean = false
    override fun isDone(): Boolean = true
    override fun isCancelled(): Boolean = false
}

sealed class ValueOrError<out R> {
    class Value<out R>(val value: R): ValueOrError<R>()
    class Error(val error: Throwable): ValueOrError<Nothing>()
}

sealed class ResultOrAsync<out R> {
    class Result<out R>(val result: ValueOrError<R>): ResultOrAsync<R>()
    class Async: ResultOrAsync<Nothing>()
}
