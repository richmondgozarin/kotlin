/*
 * Copyright 2010-2015 JetBrains s.r.o.
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

package org.jetbrains.kotlin.idea.intentions

import com.intellij.openapi.editor.Editor
import org.jetbrains.kotlin.idea.caches.resolve.getResolutionFacade
import org.jetbrains.kotlin.idea.inspections.IntentionBasedInspection
import org.jetbrains.kotlin.idea.resolve.frontendService
import org.jetbrains.kotlin.idea.util.getResolutionScope
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingTraceContext
import org.jetbrains.kotlin.resolve.bindingContextUtil.getDataFlowInfoBefore
import org.jetbrains.kotlin.resolve.calls.CallResolver
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCall
import org.jetbrains.kotlin.resolve.calls.util.DelegatingCall
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.types.isError
import org.jetbrains.kotlin.types.typeUtil.isSubtypeOf

class RemoveExplicitTypeArgumentsInspection : IntentionBasedInspection<KtTypeArgumentList>(RemoveExplicitTypeArgumentsIntention::class)

class RemoveExplicitTypeArgumentsIntention : SelfTargetingOffsetIndependentIntention<KtTypeArgumentList>(
        KtTypeArgumentList::class.java,
        "Remove explicit type arguments"
) {
    companion object {

        private fun KtCallExpression.hasExplicitExpectedType(): Boolean {
            // todo Check with expected type for other expressions
            // If always use expected type from trace there is a problem with nested calls:
            // the expression type for them can depend on their explicit type arguments (via outer call),
            // therefore we should resolve outer call with erased type arguments for inner call
            val parent = parent
            return when (parent) {
                is KtProperty -> parent.initializer == this && parent.typeReference != null
                is KtDeclarationWithBody -> parent.bodyExpression == this
                is KtReturnExpression -> true
                is KtValueArgument -> (parent.parent.parent as? KtCallExpression)?.let {
                    it.typeArgumentList != null || it.hasExplicitExpectedType()
                }?: false
                else -> false
            }
        }

        fun isApplicableTo(element: KtTypeArgumentList, approximateFlexible: Boolean): Boolean {
            val callExpression = element.parent as? KtCallExpression ?: return false
            if (callExpression.typeArguments.isEmpty()) return false

            val resolutionFacade = callExpression.getResolutionFacade()
            val context = resolutionFacade.analyze(callExpression, BodyResolveMode.PARTIAL)
            val calleeExpression = callExpression.calleeExpression ?: return false
            val scope = calleeExpression.getResolutionScope(context, resolutionFacade)
            val originalCall = callExpression.getResolvedCall(context) ?: return false
            val untypedCall = CallWithoutTypeArgs(originalCall.call)

            val expectedTypeIsExplicitInCode = callExpression.hasExplicitExpectedType()
            val expectedType = if (expectedTypeIsExplicitInCode) {
                context[BindingContext.EXPECTED_EXPRESSION_TYPE, callExpression] ?: TypeUtils.NO_EXPECTED_TYPE
            }
            else {
                TypeUtils.NO_EXPECTED_TYPE
            }
            val dataFlow = context.getDataFlowInfoBefore(callExpression)
            val callResolver = resolutionFacade.frontendService<CallResolver>()
            val resolutionResults = callResolver.resolveFunctionCall(
                    BindingTraceContext(), scope, untypedCall, expectedType, dataFlow, false)
            if (!resolutionResults.isSingleResult) {
                return false
            }

            val args = originalCall.typeArguments
            val newArgs = resolutionResults.resultingCall.typeArguments

            fun equalTypes(explicitArgType: KotlinType, implicitArgType: KotlinType): Boolean {
                return !implicitArgType.isError && if (approximateFlexible) {
                    implicitArgType.isSubtypeOf(explicitArgType)
                }
                else {
                    explicitArgType == implicitArgType
                }
            }

            return args.size == newArgs.size && args.values.zip(newArgs.values).all { (argType, newArgType) ->
                equalTypes(argType, newArgType)
            }
        }
    }

    override fun isApplicableTo(element: KtTypeArgumentList): Boolean {
        return isApplicableTo(element, approximateFlexible = false)
    }

    private class CallWithoutTypeArgs(call: Call) : DelegatingCall(call) {
        override fun getTypeArguments() = emptyList<KtTypeProjection>()
        override fun getTypeArgumentList() = null
    }

    override fun applyTo(element: KtTypeArgumentList, editor: Editor?) {
        element.delete()
    }
}