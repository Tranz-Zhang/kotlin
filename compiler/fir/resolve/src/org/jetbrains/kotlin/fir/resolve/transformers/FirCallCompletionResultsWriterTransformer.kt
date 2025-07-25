/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.transformers

import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.builtins.functions.FunctionTypeKind
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.fakeElement
import org.jetbrains.kotlin.fir.*
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.synthetic.FirSyntheticProperty
import org.jetbrains.kotlin.fir.declarations.utils.isInline
import org.jetbrains.kotlin.fir.diagnostics.ConeDiagnostic
import org.jetbrains.kotlin.fir.diagnostics.ConeSimpleDiagnostic
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.expressions.builder.buildSamConversionExpression
import org.jetbrains.kotlin.fir.expressions.builder.buildSpreadArgumentExpression
import org.jetbrains.kotlin.fir.expressions.impl.FirResolvedArgumentList
import org.jetbrains.kotlin.fir.references.FirNamedReference
import org.jetbrains.kotlin.fir.references.FirResolvedErrorReference
import org.jetbrains.kotlin.fir.references.builder.buildResolvedCallableReference
import org.jetbrains.kotlin.fir.references.builder.buildResolvedNamedReference
import org.jetbrains.kotlin.fir.resolve.*
import org.jetbrains.kotlin.fir.resolve.calls.*
import org.jetbrains.kotlin.fir.resolve.calls.candidate.Candidate
import org.jetbrains.kotlin.fir.resolve.calls.candidate.FirErrorReferenceWithCandidate
import org.jetbrains.kotlin.fir.resolve.calls.candidate.FirNamedReferenceWithCandidate
import org.jetbrains.kotlin.fir.resolve.calls.candidate.candidate
import org.jetbrains.kotlin.fir.resolve.calls.stages.TypeArgumentMapping
import org.jetbrains.kotlin.fir.resolve.dfa.FirDataFlowAnalyzer
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.FirAnonymousFunctionReturnExpressionInfo
import org.jetbrains.kotlin.fir.resolve.diagnostics.*
import org.jetbrains.kotlin.fir.resolve.inference.FirTypeVariablesAfterPCLATransformer
import org.jetbrains.kotlin.fir.resolve.substitution.ChainedSubstitutor
import org.jetbrains.kotlin.fir.resolve.substitution.ConeSubstitutor
import org.jetbrains.kotlin.fir.resolve.substitution.createTypeSubstitutorByTypeConstructor
import org.jetbrains.kotlin.fir.resolve.substitution.substitutorByMap
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.*
import org.jetbrains.kotlin.fir.scopes.CallableCopyTypeCalculator
import org.jetbrains.kotlin.fir.scopes.impl.ConvertibleIntegerOperators.binaryOperatorsWithSignedArgument
import org.jetbrains.kotlin.fir.scopes.impl.FirClassSubstitutionScope
import org.jetbrains.kotlin.fir.scopes.impl.isWrappedIntegerOperator
import org.jetbrains.kotlin.fir.scopes.impl.isWrappedIntegerOperatorForUnsignedType
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.types.builder.buildErrorTypeRef
import org.jetbrains.kotlin.fir.types.builder.buildStarProjection
import org.jetbrains.kotlin.fir.types.builder.buildTypeProjectionWithVariance
import org.jetbrains.kotlin.fir.types.impl.ConeTypeParameterTypeImpl
import org.jetbrains.kotlin.fir.visitors.FirTransformer
import org.jetbrains.kotlin.fir.visitors.TransformData
import org.jetbrains.kotlin.fir.visitors.transformSingle
import org.jetbrains.kotlin.resolve.calls.inference.model.InferredEmptyIntersection
import org.jetbrains.kotlin.resolve.calls.tower.ApplicabilityDetail
import org.jetbrains.kotlin.resolve.calls.tower.isSuccess
import org.jetbrains.kotlin.types.TypeApproximatorConfiguration
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.types.model.TypeConstructorMarker
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstanceOrNull
import org.jetbrains.kotlin.utils.addToStdlib.runIf
import org.jetbrains.kotlin.utils.addToStdlib.runUnless

class FirCallCompletionResultsWriterTransformer(
    override val session: FirSession,
    private val scopeSession: ScopeSession,
    private val finalSubstitutor: ConeSubstitutor,
    private val typeCalculator: ReturnTypeCalculator,
    private val typeApproximator: ConeTypeApproximator,
    private val dataFlowAnalyzer: FirDataFlowAnalyzer,
    private val integerOperatorApproximator: IntegerLiteralAndOperatorApproximationTransformer,
    private val samResolver: FirSamResolver,
    private val context: BodyResolveContext,
    private val mode: Mode = Mode.Normal,
) : FirAbstractTreeTransformer<ExpectedArgumentType?>(phase = FirResolvePhase.IMPLICIT_TYPES_BODY_RESOLVE) {

    private fun finallySubstituteOrNull(
        type: ConeKotlinType,
        // Substitutor from type variables (not type parameters)
        substitutor: ConeSubstitutor = finalSubstitutor,
    ): ConeKotlinType? {
        val result = substitutor.substituteOrNull(type)
        if (result == null && type is ConeIntegerLiteralType) {
            return type.approximateIntegerLiteralType()
        }
        return result?.approximateIntegerLiteralType()
    }

    private fun finallySubstituteOrSelf(type: ConeKotlinType): ConeKotlinType {
        return finallySubstituteOrNull(type) ?: type
    }

    private val arrayOfCallTransformer = FirArrayOfCallTransformer()
    private var enableArrayOfCallTransformation = false

    enum class Mode {
        Normal, DelegatedPropertyCompletion
    }

    private inline fun <T> withFirArrayOfCallTransformer(block: () -> T): T {
        enableArrayOfCallTransformation = true
        return try {
            block()
        } finally {
            enableArrayOfCallTransformation = false
        }
    }

    private fun <T : FirQualifiedAccessExpression> prepareQualifiedTransform(
        qualifiedAccessExpression: T, calleeReference: FirNamedReferenceWithCandidate,
    ): T {
        val subCandidate = calleeReference.candidate

        subCandidate.updateSubstitutedMemberIfReceiverContainsTypeVariable()

        val declaration = subCandidate.symbol.fir
        val typeArguments = computeTypeArguments(qualifiedAccessExpression, subCandidate)
        val type = if (declaration is FirCallableDeclaration) {
            val calculated = typeCalculator.tryCalculateReturnType(declaration)
            if (calculated !is FirErrorTypeRef) {
                calculated.coneType
            } else {
                ConeErrorType(calculated.diagnostic)
            }
        } else {
            // this branch is for cases when we have
            // some invalid qualified access expression itself.
            // e.g. `T::toString` where T is a generic type.
            // in these cases we should report an error on
            // the calleeReference.source which is not a fake source.
            ConeErrorType(
                when (declaration) {
                    is FirTypeParameter -> ConeTypeParameterInQualifiedAccess(declaration.symbol)
                    else -> ConeSimpleDiagnostic("Callee reference to candidate without return type: ${declaration.render()}")
                }
            )
        }

        if (mode == Mode.DelegatedPropertyCompletion) {
            // Update type for `$delegateField` in `$$delegateField.get/setValue()` calls inside accessors
            val typeUpdater = TypeUpdaterForPCLAAndDelegateReceivers()
            qualifiedAccessExpression.transformExplicitReceiver(typeUpdater, null)
        }

        var dispatchReceiver = subCandidate.dispatchReceiverExpression()
        var extensionReceiver = subCandidate.chosenExtensionReceiverExpression()
        if (!declaration.isWrappedIntegerOperator()) {
            val expectedDispatchReceiverType = (declaration as? FirCallableDeclaration)?.dispatchReceiverType
            // If the candidate is not successful and extension receiver is Integer literal, it should be approximated
            //   to default type (Int), not expected type of extension receiver
            val expectedExtensionReceiverType = runIf(subCandidate.isSuccessful) {
                (declaration as? FirCallableDeclaration)?.receiverParameter?.typeRef?.coneType
            }
            dispatchReceiver = dispatchReceiver?.transformSingle(integerOperatorApproximator, expectedDispatchReceiverType)
            extensionReceiver = extensionReceiver?.transformSingle(integerOperatorApproximator, expectedExtensionReceiverType)
        }

        if (subCandidate.usedOuterCs) {
            val updaterForThisReferences = TypeUpdaterForPCLAAndDelegateReceivers()
            dispatchReceiver = dispatchReceiver?.transformSingle(updaterForThisReferences, null)
            extensionReceiver = extensionReceiver?.transformSingle(updaterForThisReferences, null)
        }

        qualifiedAccessExpression.apply {
            replaceCalleeReference(calleeReference.toResolvedReference())
            replaceDispatchReceiver(dispatchReceiver)
            replaceExtensionReceiver(extensionReceiver)
            replaceExplicitReceiverIfNecessary(dispatchReceiver, subCandidate)
        }

        qualifiedAccessExpression.replaceContextArguments(subCandidate.contextArguments())

        subCandidate.diagnostics.firstIsInstanceOrNull<NotFunctionAsOperator>()?.let { propertyAsOperator ->
            val coneNotFunctionAsOperator = ConeNotFunctionAsOperator(propertyAsOperator.symbol)
            val nonFatalDiagnostics: List<ConeDiagnostic> = buildList {
                addAll(qualifiedAccessExpression.nonFatalDiagnostics)
                add(coneNotFunctionAsOperator)
            }
            qualifiedAccessExpression.replaceNonFatalDiagnostics(nonFatalDiagnostics)
        }

        qualifiedAccessExpression.replaceConeTypeOrNull(type)
        qualifiedAccessExpression.replaceTypeArguments(typeArguments)

        runPCLARelatedTasksForCandidate(subCandidate)

        session.lookupTracker?.recordTypeResolveAsLookup(type, qualifiedAccessExpression.source, context.file.source)
        return qualifiedAccessExpression
    }

    private fun runPCLARelatedTasksForCandidate(candidate: Candidate) {
        // NB: The order is important here, especially for the case of delegated var property with an implicit type.
        // (see usages of `isImplicitTypedProperty` at FirDeclarationsResolveTransformer.transformPropertyAccessorsWithDelegate)
        for (postponedCall in candidate.postponedPCLACalls) {
            postponedCall.expression.transformSingle(this, null)
        }

        // Currently, those callbacks are only from nested FirDelegatedPropertyInferenceSession.
        // They update the property / accessor types _and_ resolve setter if it wasn't yet resolved (again implicitly typed delegated var).
        // It should be done _after_ completion of the delegation call.
        for (callback in candidate.onPCLACompletionResultsWritingCallbacks) {
            callback(finalSubstitutor)
        }

        // TODO: Be aware of exponent
        // This should happen after `onPCLACompletionResultsWritingCallbacks`,
        // to guarantee that the setter of implicitly typed delegated var is resolved.
        val firStubTypeTransformer = FirTypeVariablesAfterPCLATransformer(finalSubstitutor)
        for (lambda in candidate.lambdasAnalyzedWithPCLA) {
            lambda.transformSingle(firStubTypeTransformer, null)
        }
    }

    /**
     * Currently, it's only necessary for delegate inference, e.g. when the delegate expression returns some generic type
     * with non-fixed yet type variables and inside its member scope we find the `getValue` function that might still contain
     * the type variables, too and they even might be used to adding some constraints for them.
     *
     * After the completion ends and all the variables are fixed, this member candidate still contains them, so what this function does
     * is replace the candidate from Delegate<Tv, ...> scope to the same candidate from Delegate<ResultTypeForT, ..>.
     *
     * The fun fact is that it wasn't necessary before Delegate Inference refactoring because there were stub types left and FIR2IR
     * handled them properly as equal-to-anything unlike the type variable types.
     *
     * See codegen/box/delegatedProperty/noTypeVariablesLeft.kt
     *
     * That all looks a bit ugly, but there are not many options.
     * In an ideal world, we wouldn't have substitution overrides in FIR, but instead used a pair original symbol and substitution
     * everywhere, but we're not there yet.
     *
     * TODO: In future, it would be nice to get rid of it and there's actually a way to do it – not using substitution overrides (see KT-61618)
     */
    private fun Candidate.updateSubstitutedMemberIfReceiverContainsTypeVariable() {
        val updatedSymbol = symbol.updateSubstitutedMemberIfReceiverContainsTypeVariable(usedOuterCs) ?: return
        val oldSymbol = symbol

        @OptIn(Candidate.UpdatingCandidateInvariants::class)
        updateSymbol(updatedSymbol)

        check(updatedSymbol is FirCallableSymbol<*>)

        @OptIn(Candidate.UpdatingCandidateInvariants::class)
        updateSubstitutor(
            substitutorByMap(
                updatedSymbol.typeParameterSymbols.zip(freshVariables).associate { (typeParameter, typeVariable) ->
                    typeParameter to typeVariable.defaultType
                },
                session,
            )
        )

        if (updatedSymbol !is FirFunctionSymbol) return
        require(oldSymbol is FirFunctionSymbol)

        val oldArgumentMapping = argumentMapping
        val oldValueParametersToNewMap = oldSymbol.valueParameterSymbols.zip(updatedSymbol.valueParameterSymbols).toMap()

        @OptIn(Candidate.UpdatingCandidateInvariants::class)
        updateArgumentMapping(oldArgumentMapping.mapValuesTo(linkedMapOf()) { oldValueParametersToNewMap[it.value.symbol]!!.fir })
    }

    private fun FirBasedSymbol<*>.updateSubstitutedMemberIfReceiverContainsTypeVariable(usedOuterCs: Boolean): FirBasedSymbol<*>? {
        // TODO: Add assertion that this function returns not-null only for BI and delegation inference
        if (mode != Mode.DelegatedPropertyCompletion && !usedOuterCs) return null

        val fir = fir
        if (fir !is FirCallableDeclaration) return null

        val dispatchReceiverType = fir.dispatchReceiverType ?: return null
        val updatedDispatchReceiverType = finalSubstitutor.substituteOrNull(dispatchReceiverType) ?: return null

        val scope =
            updatedDispatchReceiverType.scope(
                session,
                scopeSession,
                CallableCopyTypeCalculator.DoNothing,
                FirResolvePhase.STATUS
            ) as? FirClassSubstitutionScope ?: return null

        val original = fir.originalForSubstitutionOverride ?: return null

        if (fir is FirSyntheticProperty && fir.symbol is FirSimpleSyntheticPropertySymbol && original is FirSyntheticProperty) {
            var result: FirBasedSymbol<*>? = null
            FirSyntheticPropertiesScope.createIfSyntheticNamesProviderIsDefined(session, updatedDispatchReceiverType, scope)
                ?.processPropertiesByName(fir.name) {
                    val newProperty = it.fir as? FirSyntheticProperty ?: return@processPropertiesByName
                    val originalForNew = newProperty.originalForSubstitutionOverride ?: return@processPropertiesByName
                    if (originalForNew.getter.delegate == original.getter.delegate) {
                        check(result == null)
                        result = it
                    }
                }

            return result ?: error("Not found synthetic property: ${fir.renderWithType()}")
        }

        return findSingleSubstitutedSymbolWithOriginal(original.symbol) { processor ->
            when (original) {
                is FirSimpleFunction -> scope.processFunctionsByName(original.name, processor)
                is FirProperty -> scope.processPropertiesByName(original.name, processor)
                is FirConstructor -> scope.processDeclaredConstructors(processor)
                else -> error("Unexpected declaration kind ${original.render()}")
            }
        }
    }

    private fun findSingleSubstitutedSymbolWithOriginal(
        original: FirBasedSymbol<*>,
        processCallables: ((FirCallableSymbol<*>) -> Unit) -> Unit,
    ): FirBasedSymbol<*> {
        var result: FirBasedSymbol<*>? = null

        processCallables { symbol ->
            if (symbol.originalForSubstitutionOverride == original) {
                check(result == null) {
                    "Expected single, but ${result!!.fir.render()} and ${symbol.fir.render()} found"
                }
                result = symbol
            }
        }

        return result ?: error("No symbol found for ${original.fir.render()}")
    }

    override fun transformQualifiedAccessExpression(
        qualifiedAccessExpression: FirQualifiedAccessExpression,
        data: ExpectedArgumentType?,
    ): FirStatement {
        val calleeReference = qualifiedAccessExpression.calleeReference as? FirNamedReferenceWithCandidate
            ?: return qualifiedAccessExpression
        val result = prepareQualifiedTransform(qualifiedAccessExpression, calleeReference)
        val subCandidate = calleeReference.candidate

        val resultType = result.resolvedType.substituteType(subCandidate)
        resultType.ensureResolvedTypeDeclaration(session)
        result.replaceConeTypeOrNull(resultType)
        session.lookupTracker?.recordTypeResolveAsLookup(resultType, qualifiedAccessExpression.source, context.file.source)

        result.addNonFatalDiagnostics(subCandidate)
        return result
    }

    override fun transformPropertyAccessExpression(
        propertyAccessExpression: FirPropertyAccessExpression,
        data: ExpectedArgumentType?,
    ): FirStatement {
        data?.contextSensitiveResolutionReplacements?.get(propertyAccessExpression)?.let { replacement ->
            return replacement.transformSingle(this, data)
        }
        return transformQualifiedAccessExpression(propertyAccessExpression, data)
    }

    override fun transformFunctionCall(functionCall: FirFunctionCall, data: ExpectedArgumentType?): FirStatement {
        val calleeReference = functionCall.calleeReference as? FirNamedReferenceWithCandidate
            ?: return functionCall
        val result = prepareQualifiedTransform(functionCall, calleeReference)
        val originalArgumentList = result.argumentList
        val subCandidate = calleeReference.candidate
        val resultType = result.resolvedType.substituteType(
            subCandidate,
            substitutor = subCandidate.prepareCustomReturnTypeSubstitutorForFunctionCall() ?: finalSubstitutor
        )
        val allArgs = calleeReference.computeAllArguments(originalArgumentList)
        val (regularMapping, allArgsMapping) = subCandidate.handleVarargsAndReturnResultingArgumentsMapping(allArgs)
        if (calleeReference.isError) {
            result.replaceArgumentList(buildArgumentListForErrorCall(originalArgumentList, allArgsMapping))
        } else {
            val newArgumentList = buildResolvedArgumentList(originalArgumentList, regularMapping)
            val symbol = subCandidate.symbol
            val functionIsInline =
                (symbol as? FirNamedFunctionSymbol)?.fir?.isInline == true || symbol.isArrayConstructorWithLambda
            for ((argument, parameter) in newArgumentList.mapping) {
                val lambda = (argument.unwrapArgument() as? FirAnonymousFunctionExpression)?.anonymousFunction ?: continue
                lambda.transformInlineStatus(parameter, functionIsInline, session)
            }
            result.replaceArgumentList(newArgumentList)
        }
        val expectedArgumentsTypeMapping = subCandidate.createArgumentsMapping(forErrorReference = calleeReference.isError)

        result.transformArgumentList(expectedArgumentsTypeMapping)

        result.replaceConeTypeOrNull(resultType)
        session.lookupTracker?.recordTypeResolveAsLookup(resultType, functionCall.source, context.file.source)

        if (enableArrayOfCallTransformation) {
            return arrayOfCallTransformer.transformFunctionCall(result, session)
        }

        result.addNonFatalDiagnostics(subCandidate)
        return result
    }

    private fun FirNamedReferenceWithCandidate.computeAllArguments(
        originalArgumentList: FirArgumentList,
        predefinedMapping: LinkedHashMap<FirExpression, FirValueParameter>? = null,
    ): List<FirExpression> {
        return when {
            this.isError -> originalArgumentList.arguments
            predefinedMapping != null -> predefinedMapping.keys.toList()
            else -> candidate.argumentMapping.keys.unwrapAtoms()
        }
    }


    /**
     * For Java constructors (both real and SAM ones) call with explicit type arguments, replace relevant values with non-flexible
     * counterparts.
     *
     * That hack is necessary because
     * at [org.jetbrains.kotlin.fir.resolve.calls.CreateFreshTypeVariableSubstitutorStage.getTypePreservingFlexibilityWrtTypeVariable]
     * we add flexible type constraints even for explicit type arguments.
     *
     * It's necessary because of lack of KT-59138 we need to preserve argument-lambda parameters flexible (see KT-67999), even for nullable
     * type argument.
     *
     * But at the same time, for constructors we'd like to see the invariant `typeOf<JavaClass<T>()> := JavaClass<T>`,
     * and not `JavaClass<T!>`.
     *
     * See K1 counterpart at [org.jetbrains.kotlin.resolve.calls.tower.NewAbstractResolvedCall.getSubstitutorWithoutFlexibleTypes].
     *
     * TODO: Get rid of this function once KT-59138 is fixed and the relevant feature for disabling it will be removed
     * Also we should get rid of it once [LanguageFeature.DontMakeExplicitJavaTypeArgumentsFlexible] is removed
     *
     * @return `null` for all other cases where [finalSubstitutor] should be used
     */
    private fun Candidate.prepareCustomReturnTypeSubstitutorForFunctionCall(): ConeSubstitutor? {
        if (typeArgumentMapping == TypeArgumentMapping.NoExplicitArguments) return null
        if (session.languageVersionSettings.supportsFeature(LanguageFeature.DontMakeExplicitJavaTypeArgumentsFlexible)) return null

        val symbol = symbol
        // We're only interested in Java constructors (both real and SAM ones)
        if (!symbol.isJavaConstructor() && !symbol.isSyntheticSamConstructor()) return null

        val baseSubstitutor = finalSubstitutor
        val overridingMap = mutableMapOf<TypeConstructorMarker, ConeKotlinType>()

        for ((index, freshVariable) in freshVariables.withIndex()) {
            val baseTypeArgument = baseSubstitutor.substituteOrNull(freshVariable.defaultType) ?: continue
            if (baseTypeArgument !is ConeFlexibleType) continue

            val typeArgument = typeArgumentMapping[index]
            if (typeArgument is FirPlaceholderProjection) continue
            val explicitArgument = typeArgument.toConeTypeProjection().type ?: continue

            overridingMap[freshVariable.typeConstructor] =
                baseTypeArgument.withNullabilityOf(explicitArgument, session.typeContext)
        }

        if (overridingMap.isEmpty()) return null

        return ChainedSubstitutor(
            createTypeSubstitutorByTypeConstructor(overridingMap, session.typeContext, approximateIntegerLiterals = false),
            baseSubstitutor,
        )
    }

    private fun FirBasedSymbol<*>.isJavaConstructor(): Boolean {
        if (this !is FirConstructorSymbol) return false

        return this.unwrapUseSiteSubstitutionOverrides().origin == FirDeclarationOrigin.Enhancement
    }

    private fun FirBasedSymbol<*>.isSyntheticSamConstructor(): Boolean {
        if (this !is FirSyntheticFunctionSymbol) return false

        return this.unwrapUseSiteSubstitutionOverrides().origin == FirDeclarationOrigin.SamConstructor
    }

    private fun FirCall.transformArgumentList(
        expectedArgumentsTypeMapping: ExpectedArgumentType.ArgumentsMap?,
    ) {
        val mapping = (argumentList as? FirResolvedArgumentList)?.mapping

        class ArgumentTransformer : FirTransformer<Nothing?>() {
            override fun <E : FirElement> transformElement(element: E, data: Nothing?): E {
                // We want to handle only the most top-level "real" expressions
                // We only recursively transform named, spread, lambda argument and vararg expressions.
                if (element is FirWrappedArgumentExpression || element is FirVarargArgumentsExpression) {
                    @Suppress("UNCHECKED_CAST")
                    return element.transformChildren(this, null) as E
                }

                // Once we encounter the first "real" expression, we delegate to the outer transformer.
                val transformed =
                    element.transformSingle(this@FirCallCompletionResultsWriterTransformer, expectedArgumentsTypeMapping).let {
                        expectedArgumentsTypeMapping?.contextSensitiveResolutionReplacements?.get(it) ?: it
                    }

                if (transformed is FirExpression) {
                    // Finally, the result can be wrapped in a SAM conversion if necessary.
                    val key = (element as? FirAnonymousFunctionExpression)?.anonymousFunction ?: element
                    expectedArgumentsTypeMapping?.samConversions?.get(key)?.let { samInfo ->
                        @Suppress("UNCHECKED_CAST")
                        return transformed.wrapInSamExpression(
                            expectedArgumentType = samInfo.samType,
                            usesFunctionKindConversion = key in expectedArgumentsTypeMapping.argumentsWithFunctionKindConversion
                        ) as E
                    }
                }

                @Suppress("UNCHECKED_CAST")
                return transformed as E
            }

            override fun transformNamedArgumentExpression(
                namedArgumentExpression: FirNamedArgumentExpression,
                data: Nothing?,
            ): FirStatement {
                val expression = transformElement(namedArgumentExpression.expression, data)
                val parameter = mapping?.get(namedArgumentExpression)
                return if (namedArgumentExpression.isSpread || parameter?.isVararg == true) {
                    buildSpreadArgumentExpression {
                        this.source = namedArgumentExpression.source
                        this.expression = expression
                        this.isNamed = true
                        this.isFakeSpread = !namedArgumentExpression.isSpread
                    }
                } else {
                    expression
                }
            }
        }

        argumentList.transformArguments(ArgumentTransformer(), null)
    }

    private fun FirExpression.wrapInSamExpression(
        expectedArgumentType: ConeKotlinType,
        usesFunctionKindConversion: Boolean,
    ): FirExpression {
        return buildSamConversionExpression {
            expression = this@wrapInSamExpression
            coneTypeOrNull = expectedArgumentType.withNullabilityOf(resolvedType, session.typeContext)
                .let {
                    typeApproximator.approximateToSuperType(
                        it,
                        TypeApproximatorConfiguration.TypeArgumentApproximationAfterCompletionInK2
                    ) ?: it
                }
            this.usesFunctionKindConversion = usesFunctionKindConversion
            source = this@wrapInSamExpression.source?.fakeElement(KtFakeSourceElementKind.SamConversion)
        }
    }

    override fun transformAnnotationCall(
        annotationCall: FirAnnotationCall,
        data: ExpectedArgumentType?,
    ): FirStatement {
        val calleeReference = annotationCall.calleeReference as? FirNamedReferenceWithCandidate ?: return annotationCall
        annotationCall.replaceCalleeReference(calleeReference.toResolvedReference())
        val subCandidate = calleeReference.candidate
        val expectedArgumentsTypeMapping = subCandidate.createArgumentsMapping(forErrorReference = calleeReference.isError)
        val argumentMappingWithArrayOfCalls = withFirArrayOfCallTransformer {
            annotationCall.argumentList.transformArguments(this, expectedArgumentsTypeMapping)
            var index = 0
            subCandidate.argumentMapping.let {
                LinkedHashMap<FirExpression, FirValueParameter>(it.size).let { newMapping ->
                    subCandidate.argumentMapping.mapKeysTo(newMapping) { (_, _) ->
                        annotationCall.argumentList.arguments[index++]
                    }
                }
            }
        }
        val allArgs = calleeReference.computeAllArguments(annotationCall.argumentList, argumentMappingWithArrayOfCalls)
        val (regularMapping, allArgsMapping) = subCandidate.handleVarargsAndReturnResultingArgumentsMapping(
            allArgs,
            precomputedArgumentMapping = argumentMappingWithArrayOfCalls
        )
        if (calleeReference.isError) {
            annotationCall.replaceArgumentList(buildArgumentListForErrorCall(annotationCall.argumentList, allArgsMapping))
        } else {
            regularMapping.let {
                annotationCall.replaceArgumentList(buildResolvedArgumentList(annotationCall.argumentList, it))
            }
        }

        annotationCall.transformArgumentList(expectedArgumentsTypeMapping = null)
        return annotationCall
    }

    override fun transformErrorAnnotationCall(errorAnnotationCall: FirErrorAnnotationCall, data: ExpectedArgumentType?): FirStatement {
        return transformAnnotationCall(errorAnnotationCall, data)
    }

    private data class ResultingArgumentsMapping(
        val regularMapping: LinkedHashMap<FirExpression, FirValueParameter>,
        val allArgsMapping: LinkedHashMap<FirExpression, FirValueParameter?>,
    )

    /**
     * The function does two things:
     * 1. Changes [Candidate.argumentMapping] if at least one vararg is presented.
     *    The new mapping wraps vararg arguments
     * 2. Returns mapping of **all** args to parameters. Since args can be missing in the [Candidate.argumentMapping],
     *    the returned collection may contain `null`s. Generally speaking, it should only happen only in some cases when
     *    `calleeReference.isError` is `true` (see function usages)
     */
    private fun Candidate.handleVarargsAndReturnResultingArgumentsMapping(
        argumentList: List<FirExpression>,
        precomputedArgumentMapping: LinkedHashMap<FirExpression, FirValueParameter>? = null,
    ): ResultingArgumentsMapping {
        val argumentMapping = precomputedArgumentMapping ?: this.argumentMapping.unwrapAtoms()
        val varargParameter = argumentMapping.values.firstOrNull { it.isVararg }
        return if (varargParameter != null) {
            // Create a FirVarargArgumentExpression for the vararg arguments
            val varargParameterTypeRef = varargParameter.returnTypeRef
            val resolvedArrayType = varargParameterTypeRef.substitute(this)
            val argumentMappingWithAllArgs =
                remapArgumentsWithVararg(varargParameter, resolvedArrayType, argumentMapping, argumentList)
            ResultingArgumentsMapping(
                argumentMappingWithAllArgs.filterValuesNotNull(),
                argumentMappingWithAllArgs
            )
        } else {
            ResultingArgumentsMapping(
                argumentMapping,
                argumentList.associateWithTo(LinkedHashMap()) { argumentMapping[it] }
            )
        }
    }

    private fun <D : FirExpression> D.replaceTypeWithSubstituted(
        calleeReference: FirNamedReferenceWithCandidate,
        typeRef: FirResolvedTypeRef,
    ): D {
        val resultType = typeRef.coneType.substituteType(calleeReference.candidate)
        replaceConeTypeOrNull(resultType)
        session.lookupTracker?.recordTypeResolveAsLookup(resultType, source, context.file.source)
        return this
    }

    private fun ConeKotlinType.substituteType(
        candidate: Candidate,
        // Substitutor from type variables (not type parameters)
        substitutor: ConeSubstitutor = finalSubstitutor,
    ): ConeKotlinType {
        // Type parameters are replaced with type variables
        val initialType = candidate.substitutor.substituteOrSelf(this)
        // Type variables are replaced with final type arguments
        val substitutedType = finallySubstituteOrNull(initialType, substitutor) ?: initialType
        // Everything is approximated
        val finalType = typeApproximator.approximateToSuperType(
            type = substitutedType,
            TypeApproximatorConfiguration.IntermediateApproximationToSupertypeAfterCompletionInK2,
        ) ?: substitutedType

        // This is probably a temporary hack, but it seems necessary because elvis has that attribute and it may leak further like
        // fun <E> foo() = materializeNullable<E>() ?: materialize<E>() // `foo` return type unexpectedly gets inferred to @Exact E
        //
        // In FE1.0, it's not necessary since the annotation for elvis have some strange form (see org.jetbrains.kotlin.resolve.descriptorUtil.AnnotationsWithOnly)
        // that is not propagated further.
        return finalType.removeExactAttribute()
    }

    private fun ConeKotlinType.removeExactAttribute(): ConeKotlinType {
        if (attributes.contains(CompilerConeAttributes.Exact)) {
            return withAttributes(attributes.remove(CompilerConeAttributes.Exact))
        }

        return this
    }

    override fun transformSafeCallExpression(
        safeCallExpression: FirSafeCallExpression,
        data: ExpectedArgumentType?,
    ): FirStatement {
        safeCallExpression.transformSelector(
            this,
            data?.getExpectedType(
                safeCallExpression
            )?.toExpectedType(data.contextSensitiveResolutionReplacements)
        )

        safeCallExpression.propagateTypeFromQualifiedAccessAfterNullCheck(session, context.file)

        return safeCallExpression
    }

    override fun transformCallableReferenceAccess(
        callableReferenceAccess: FirCallableReferenceAccess,
        data: ExpectedArgumentType?,
    ): FirStatement {
        val calleeReference =
            callableReferenceAccess.calleeReference as? FirNamedReferenceWithCandidate ?: return callableReferenceAccess
        val subCandidate = calleeReference.candidate
        val typeArguments = computeTypeArguments(callableReferenceAccess, subCandidate)

        val initialType = calleeReference.candidate.substitutor.substituteOrSelf(callableReferenceAccess.resolvedType)
        val finalType = finallySubstituteOrSelf(initialType)

        callableReferenceAccess.replaceConeTypeOrNull(finalType)
        callableReferenceAccess.replaceTypeArguments(typeArguments)
        session.lookupTracker?.recordTypeResolveAsLookup(
            finalType,
            callableReferenceAccess.source ?: callableReferenceAccess.source,
            context.file.source
        )

        val resolvedReference = when (calleeReference) {
            is FirErrorReferenceWithCandidate -> calleeReference.toErrorReference(calleeReference.diagnostic)
            else -> buildResolvedCallableReference {
                source = calleeReference.source
                name = calleeReference.name
                resolvedSymbol = calleeReference.candidateSymbol
                inferredTypeArguments.addAll(computeTypeArgumentTypes(calleeReference.candidate))
                mappedArguments = subCandidate.callableReferenceAdaptation?.mappedArguments?.mapValues { (_, argument) ->
                    argument.map { it.expression }
                } ?: emptyMap()
            }
        }

        var dispatchReceiver = subCandidate.dispatchReceiverExpression()
        var extensionReceiver = subCandidate.chosenExtensionReceiverExpression()

        if (subCandidate.usedOuterCs) {
            val updaterForThisReferences = TypeUpdaterForPCLAAndDelegateReceivers()
            dispatchReceiver = dispatchReceiver?.transformSingle(updaterForThisReferences, null)
            extensionReceiver = extensionReceiver?.transformSingle(updaterForThisReferences, null)
        }

        return callableReferenceAccess.apply {
            replaceCalleeReference(resolvedReference)
            replaceDispatchReceiver(dispatchReceiver)
            replaceExtensionReceiver(extensionReceiver)
            replaceExplicitReceiverIfNecessary(dispatchReceiver, subCandidate)
            addNonFatalDiagnostics(subCandidate)
        }
    }

    override fun transformSmartCastExpression(smartCastExpression: FirSmartCastExpression, data: ExpectedArgumentType?): FirStatement {
        return smartCastExpression.transformOriginalExpression(this, data)
    }

    private inner class TypeUpdaterForPCLAAndDelegateReceivers : FirTransformer<Any?>() {
        override fun <E : FirElement> transformElement(element: E, data: Any?): E {
            return element
        }

        override fun transformThisReceiverExpression(thisReceiverExpression: FirThisReceiverExpression, data: Any?): FirStatement {
            return transformTypeRefForQualifiedAccess(thisReceiverExpression)
        }

        override fun transformQualifiedAccessExpression(
            qualifiedAccessExpression: FirQualifiedAccessExpression,
            data: Any?,
        ): FirStatement {
            return transformTypeRefForQualifiedAccess(qualifiedAccessExpression)
        }

        override fun transformPropertyAccessExpression(propertyAccessExpression: FirPropertyAccessExpression, data: Any?): FirStatement {
            return transformQualifiedAccessExpression(propertyAccessExpression, data)
        }

        private fun transformTypeRefForQualifiedAccess(qualifiedAccessExpression: FirQualifiedAccessExpression): FirQualifiedAccessExpression {
            val originalType = qualifiedAccessExpression.resolvedType
            val substitutedReceiverType = finallySubstituteOrNull(originalType) ?: return qualifiedAccessExpression
            qualifiedAccessExpression.replaceConeTypeOrNull(substitutedReceiverType)
            session.lookupTracker?.recordTypeResolveAsLookup(substitutedReceiverType, qualifiedAccessExpression.source, context.file.source)
            return qualifiedAccessExpression
        }
    }

    private fun FirTypeRef.substitute(candidate: Candidate): ConeKotlinType {
        return coneType.substitute(candidate)
    }

    private fun ConeKotlinType.substitute(candidate: Candidate): ConeKotlinType {
        return finallySubstituteOrSelf(candidate.substitutor.substituteOrSelf(this))
    }

    private fun Candidate.createArgumentsMapping(forErrorReference: Boolean): ExpectedArgumentType.ArgumentsMap? {
        val lambdasReturnType = postponedAtoms.filterIsInstance<ConeResolvedLambdaAtom>().associate {
            Pair(it.anonymousFunction, finallySubstituteOrSelf(substitutor.substituteOrSelf(it.returnType)))
        }

        val isIntegerOperator = symbol.isWrappedIntegerOperator()

        var samConversions: MutableMap<FirElement, FirSamResolver.SamConversionInfo>? = null
        val arguments = argumentMapping.flatMap { (atom, valueParameter) ->
            val argument = atom.expression
            val expectedType = when {
                isIntegerOperator -> ConeIntegerConstantOperatorTypeImpl(
                    isUnsigned = symbol.isWrappedIntegerOperatorForUnsignedType() && callInfo.name in binaryOperatorsWithSignedArgument,
                    isMarkedNullable = false
                )
                valueParameter.isVararg -> valueParameter.returnTypeRef.substitute(this).varargElementType()
                else -> valueParameter.returnTypeRef.substitute(this)
            }

            argument.unwrapAndFlattenArgument(flattenArrays = false).map {
                val element: FirElement = (it as? FirAnonymousFunctionExpression)?.anonymousFunction ?: it
                samConversionInfosOfArguments?.get(it)?.let { samInfo ->
                    if (samConversions == null) samConversions = mutableMapOf()
                    samConversions[element] = FirSamResolver.SamConversionInfo(
                        functionalType = samInfo.functionalType.substituteType(this),
                        samType = samInfo.samType.substituteType(this)
                    )
                }
                element to expectedType
            }
        }.toMap()

        val contextSensitiveResolutionReplacements = this@createArgumentsMapping.contextSensitiveResolutionReplacements

        if (lambdasReturnType.isEmpty() && arguments.isEmpty() && contextSensitiveResolutionReplacements.isNullOrEmpty()) return null
        return ExpectedArgumentType.ArgumentsMap(
            map = arguments,
            lambdasReturnTypes = lambdasReturnType,
            samConversions = samConversions ?: emptyMap(),
            argumentsWithFunctionKindConversion = argumentsWithFunctionKindConversion ?: emptySet(),
            forErrorReference = forErrorReference,
            contextSensitiveResolutionReplacements,
        )
    }

    override fun transformDelegatedConstructorCall(
        delegatedConstructorCall: FirDelegatedConstructorCall,
        data: ExpectedArgumentType?,
    ): FirStatement {
        val calleeReference =
            delegatedConstructorCall.calleeReference as? FirNamedReferenceWithCandidate ?: return delegatedConstructorCall
        val subCandidate = calleeReference.candidate

        val originalArgumentList = delegatedConstructorCall.argumentList
        val allArgs = calleeReference.computeAllArguments(originalArgumentList)
        val (regularMapping, allArgsMapping) = subCandidate.handleVarargsAndReturnResultingArgumentsMapping(allArgs)
        if (calleeReference.isError) {
            delegatedConstructorCall.replaceArgumentList(buildArgumentListForErrorCall(originalArgumentList, allArgsMapping))
        } else {
            regularMapping.let {
                delegatedConstructorCall.replaceArgumentList(buildResolvedArgumentList(originalArgumentList, it))
            }
        }

        runPCLARelatedTasksForCandidate(subCandidate)

        val argumentsMapping = subCandidate.createArgumentsMapping(forErrorReference = calleeReference.isError)
        delegatedConstructorCall.transformArgumentList(argumentsMapping)

        return delegatedConstructorCall.apply {
            replaceCalleeReference(calleeReference.toResolvedReference())
        }
    }

    private fun computeTypeArguments(
        access: FirQualifiedAccessExpression,
        candidate: Candidate,
    ): List<FirTypeProjection> {
        val typeArguments = computeTypeArgumentTypes(candidate)
            .mapIndexed { index, typeFromFinalSubstitutor ->
                val argument = access.typeArguments.getOrNull(index)
                val type = typeFromFinalSubstitutor.storeNonFlexibleCounterpartInAttributeIfNecessary(argument)
                val sourceForTypeArgument = argument?.source
                    ?: access.calleeReference.source?.fakeElement(KtFakeSourceElementKind.ImplicitTypeArgument)
                when (argument) {
                    is FirTypeProjectionWithVariance -> {
                        val typeRef = argument.typeRef as FirResolvedTypeRef
                        buildTypeProjectionWithVariance {
                            source = sourceForTypeArgument
                            this.typeRef =
                                if (typeRef.coneType.fullyExpandedType() is ConeErrorType) typeRef else typeRef.withReplacedConeType(
                                    type
                                )
                            variance = argument.variance
                        }
                    }
                    is FirStarProjection -> {
                        buildStarProjection {
                            source = sourceForTypeArgument
                        }
                    }
                    else -> {
                        buildTypeProjectionWithVariance {
                            source = sourceForTypeArgument
                            typeRef = type.toFirResolvedTypeRef(sourceForTypeArgument)
                            variance = Variance.INVARIANT
                        }
                    }
                }
            }

        // We must ensure that all extra type arguments are preserved in the result, so that they can still be resolved later (e.g. for
        // navigation in the IDE).
        return if (typeArguments.size < access.typeArguments.size) {
            typeArguments + access.typeArguments.subList(typeArguments.size, access.typeArguments.size).map {
                if (it !is FirPlaceholderProjection) it
                else buildTypeProjectionWithVariance {
                    source = it.source
                    typeRef = buildErrorTypeRef {
                        source = it.source
                        diagnostic = ConeSimpleDiagnostic("Unmapped placeholder type argument")
                    }
                    variance = Variance.INVARIANT
                }
            }
        } else typeArguments
    }

    /**
     * @see ExplicitTypeArgumentIfMadeFlexibleSyntheticallyTypeAttribute
     * TODO: Get rid of this function once KT-59138 is fixed and the relevant feature for disabling it will be removed
     * Also we should get rid of it once [LanguageFeature.DontMakeExplicitJavaTypeArgumentsFlexible] is removed
     */
    private fun ConeKotlinType.storeNonFlexibleCounterpartInAttributeIfNecessary(
        argument: FirTypeProjection?,
    ): ConeKotlinType {
        if (this !is ConeFlexibleType) return this
        if (argument !is FirTypeProjectionWithVariance) return this
        if (session.languageVersionSettings.supportsFeature(LanguageFeature.DontMakeExplicitJavaTypeArgumentsFlexible)) return this

        return withAttributes(
            attributes.add(
                ExplicitTypeArgumentIfMadeFlexibleSyntheticallyTypeAttribute(
                    argument.typeRef.coneType.fullyExpandedType(),
                    LanguageFeature.JavaTypeParameterDefaultRepresentationWithDNN
                )
            )
        )
    }

    private fun computeTypeArgumentTypes(
        candidate: Candidate,
    ): List<ConeKotlinType> {
        val declaration = candidate.symbol.fir as? FirCallableDeclaration ?: return emptyList()

        return declaration.typeParameters.map {
            val typeParameter = ConeTypeParameterTypeImpl(it.symbol.toLookupTag(), false)
            val substitution = candidate.substitutor.substituteOrSelf(typeParameter)
            finallySubstituteOrSelf(substitution).let { substitutedType ->
                typeApproximator.approximateToSuperType(
                    substitutedType, TypeApproximatorConfiguration.TypeArgumentApproximationAfterCompletionInK2,
                ) ?: substitutedType
            }
        }
    }

    override fun transformAnonymousFunctionExpression(
        anonymousFunctionExpression: FirAnonymousFunctionExpression,
        data: ExpectedArgumentType?,
    ): FirStatement {
        return anonymousFunctionExpression.transformAnonymousFunction(this, data)
    }

    override fun transformAnonymousFunction(
        anonymousFunction: FirAnonymousFunction,
        data: ExpectedArgumentType?,
    ): FirStatement {
        val returnExpressions =
            dataFlowAnalyzer
                .returnExpressionsOfAnonymousFunction(anonymousFunction)
                .replacePostponedAtomsInReturnExpressions(data)

        /*
         * If the resolved call contains some errors, we want to consider expected type only for calculation of
         *   the functional kind of the lambda, not for its parameter or return types
         *
         * Theoretically, there is nothing wrong in using the expected type all the times, but if the expected type contains error types,
         *   they will leak inside types of lambda, which will cause some diagnostics to be duplicated (like `CANNOT_INFER_PARAMETER_TYPE`)
         * Examples of affected tests:
         * - compiler/testData/diagnostics/tests/inference/crashWithNestedLambdasRedCode.kt
         * - compiler/testData/diagnostics/tests/inference/pcla/regresssions/exponentialErrorsInCSInitial.kt
         * - compiler/testData/diagnostics/tests/resolve/lambdaAgainstTypeVariableWithConstraintAfter.kt
         */
        val containingCallIsError = (data as? ExpectedArgumentType.ArgumentsMap)?.forErrorReference == true
        val initialExpectedType = data?.getExpectedType(anonymousFunction)?.let { expectedArgumentType ->
            // From the argument mapping, the expected type of this anonymous function would be:
            when {
                // a built-in functional type, no-brainer
                expectedArgumentType.isSomeFunctionType(session) -> expectedArgumentType.lowerBoundIfFlexible()
                // fun interface (a.k.a. SAM), then unwrap it and build a functional type from that interface function
                else -> {
                    val samInfo = (data as? ExpectedArgumentType.ArgumentsMap)?.samConversions?.get(anonymousFunction)
                        ?: samResolver.getSamInfoForPossibleSamType(expectedArgumentType)
                    samInfo?.functionalType?.lowerBoundIfFlexible()
                }
            }
        }
        val expectedType = initialExpectedType?.takeUnless { containingCallIsError }
        var needUpdateLambdaType = anonymousFunction.typeRef is FirImplicitTypeRef

        val receiverParameter = anonymousFunction.receiverParameter
        val initialReceiverType = receiverParameter?.typeRef?.coneTypeSafe<ConeKotlinType>()
        val resultReceiverType = initialReceiverType?.let { finallySubstituteOrNull(it) }
        if (resultReceiverType != null) {
            receiverParameter.replaceTypeRef(
                receiverParameter.typeRef.resolvedTypeFromPrototype(
                    resultReceiverType,
                    (receiverParameter.source ?: anonymousFunction.source)?.fakeElement(KtFakeSourceElementKind.LambdaReceiver)
                )
            )
            needUpdateLambdaType = true
        }

        val initialReturnType = anonymousFunction.returnTypeRef.coneTypeSafe<ConeKotlinType>()
        val expectedReturnType = initialReturnType?.let { finallySubstituteOrSelf(it) }
            ?: runIf(returnExpressions.any { it.expression.source?.kind is KtFakeSourceElementKind.ImplicitUnit.Return })
            { session.builtinTypes.unitType.coneType }
            ?: (expectedType as? ConeClassLikeType)?.returnType(session) as? ConeClassLikeType
            ?: runUnless(containingCallIsError) { (data as? ExpectedArgumentType.ArgumentsMap)?.lambdasReturnTypes?.get(anonymousFunction) }

        val newData = expectedReturnType?.toExpectedType(data?.contextSensitiveResolutionReplacements)
        for ((expression, _) in returnExpressions) {
            expression.transformSingle(this, newData)
        }

        // TODO: Avoid recursive transformation of statements again (KT-76677)
        // The only thing that seems necessary is writing the resulting type of the block
        // from already analyzed statements.
        // On the other hand, what does "resulting type of a block" means and why it's obtained from the last statement-only
        // is a different question.
        // Currently, it only seems to be heavily used by org.jetbrains.kotlin.fir.resolve.ResolveUtilsKt.addReturnToLastStatementIfNeeded
        // below when checking the `isNothing` case.
        anonymousFunction.body?.let { transformBlock(it, newData) }

        val resultReturnType = anonymousFunction.computeReturnType(
            session,
            expectedReturnType,
            isPassedAsFunctionArgument = true,
            returnExpressions,
        )

        if (initialReturnType != resultReturnType) {
            val fakeSource = anonymousFunction.source?.fakeElement(KtFakeSourceElementKind.ImplicitFunctionReturnType)
            anonymousFunction.replaceReturnTypeRef(anonymousFunction.returnTypeRef.resolvedTypeFromPrototype(resultReturnType, fakeSource))
            session.lookupTracker?.recordTypeResolveAsLookup(anonymousFunction.returnTypeRef, anonymousFunction.source, context.file.source)
            needUpdateLambdaType = true
        }

        if (needUpdateLambdaType) {
            // When we get the FunctionTypeKind, we have to check the deserialized ConeType first because it checks both
            // class ID and annotations. On the other hand, `functionTypeKind()` checks only class ID.
            val kind = initialExpectedType?.functionTypeKindForDeserializedConeType()
                ?: initialExpectedType?.functionTypeKind(session)
                ?: anonymousFunction.typeRef.coneTypeSafe<ConeClassLikeType>()?.functionTypeKind(session)
            anonymousFunction.replaceTypeRef(anonymousFunction.constructFunctionTypeRef(session, kind))
            session.lookupTracker?.recordTypeResolveAsLookup(anonymousFunction.typeRef, anonymousFunction.source, context.file.source)
        }
        // Have to delay this until the type is written to avoid adding a return if the type is Unit.
        anonymousFunction.addReturnToLastStatementIfNeeded(session)
        return anonymousFunction
    }

    /**
     * Some postponed atoms when may require replacing one FIR nodes with another one.
     * For example, after context-sensitive resolution, FirPropertyAccessExpression may turn into FirResolvedQualifier.
     *
     * So, we need to replace them both inside FirAnonymousFunctionReturnExpressionInfo, but also transform
     * the parent node (return-expression or block for last statement in lambda).
     */
    private fun Collection<FirAnonymousFunctionReturnExpressionInfo>.replacePostponedAtomsInReturnExpressions(
        data: ExpectedArgumentType?,
    ): Collection<FirAnonymousFunctionReturnExpressionInfo> {

        val replacements = data?.contextSensitiveResolutionReplacements ?: return this

        return map { returnInfo ->
            val replacement = replacements[returnInfo.expression] ?: return@map returnInfo

            class ReturnExpressionReplacer : FirTransformer<Nothing?>() {
                override fun <E : FirElement> transformElement(element: E, data: Nothing?): E {
                    @Suppress("UNCHECKED_CAST")
                    return when {
                        element === returnInfo.expression -> replacement as E
                        else -> element
                    }
                }

                override fun transformReturnExpression(
                    returnExpression: FirReturnExpression,
                    data: Nothing?,
                ): FirStatement {
                    return returnExpression.transformResult(this, data)
                }

                override fun transformBlock(
                    block: FirBlock,
                    data: Nothing?,
                ): FirStatement {
                    return block.transformStatementsIndexed(this) { index ->
                        // Transform only the last statement
                        if (index == block.statements.lastIndex)
                            TransformData.Data(null)
                        else
                            TransformData.Nothing
                    }
                }
            }

            returnInfo.copy(
                expression = replacement,
                containingStatement =
                    returnInfo.containingStatement.transformSingle(ReturnExpressionReplacer(), null),
            )
        }
    }

    private fun ConeKotlinType.functionTypeKindForDeserializedConeType(): FunctionTypeKind? {
        val coneClassLikeType = this.lowerBoundIfFlexible() as? ConeClassLikeType ?: return null
        val classId = coneClassLikeType.classId ?: return null
        return session.functionTypeService.extractSingleExtensionKindForDeserializedConeType(classId, coneClassLikeType.customAnnotations)
    }

    override fun transformReturnExpression(
        returnExpression: FirReturnExpression,
        data: ExpectedArgumentType?,
    ): FirStatement {
        val labeledElement = returnExpression.target.labeledElement
        if (labeledElement is FirAnonymousFunction) {
            return returnExpression
        }

        val newData =
            labeledElement.returnTypeRef.coneTypeSafe<ConeKotlinType>()?.toExpectedType(data?.contextSensitiveResolutionReplacements)
        return super.transformReturnExpression(returnExpression, newData)
    }

    override fun transformBlock(block: FirBlock, data: ExpectedArgumentType?): FirStatement {
        transformElement(block, data)
        if (!block.isUnitCoerced) {
            block.writeResultType(session)
        }
        return block
    }

    // Transformations for synthetic calls generated by FirSyntheticCallGenerator

    override fun transformWhenExpression(
        whenExpression: FirWhenExpression,
        data: ExpectedArgumentType?,
    ): FirStatement {
        return transformSyntheticCall(whenExpression, data).apply {
            replaceReturnTypeIfNotExhaustive(session)
        }
    }

    override fun transformTryExpression(
        tryExpression: FirTryExpression,
        data: ExpectedArgumentType?,
    ): FirStatement {
        return transformSyntheticCall(tryExpression, data)
    }

    override fun transformCheckNotNullCall(
        checkNotNullCall: FirCheckNotNullCall,
        data: ExpectedArgumentType?,
    ): FirStatement {
        return transformSyntheticCall(checkNotNullCall, data)
    }

    override fun transformElvisExpression(
        elvisExpression: FirElvisExpression,
        data: ExpectedArgumentType?,
    ): FirStatement {
        return transformSyntheticCall(elvisExpression, data)
    }

    private inline fun <reified D> transformSyntheticCall(
        syntheticCall: D,
        data: ExpectedArgumentType?,
    ): D where D : FirResolvable, D : FirExpression {
        val calleeReference = syntheticCall.calleeReference as? FirNamedReferenceWithCandidate
        val declaration = calleeReference?.candidate?.symbol?.fir as? FirSimpleFunction

        if (calleeReference == null || declaration == null) {
            transformSyntheticCallChildren(syntheticCall, data)
            return syntheticCall
        }

        val typeRef = typeCalculator.tryCalculateReturnType(declaration)
        syntheticCall.replaceTypeWithSubstituted(calleeReference, typeRef)
        transformSyntheticCallChildren(syntheticCall, data)

        runPCLARelatedTasksForCandidate(calleeReference.candidate)

        val resolvedCalleeReference = calleeReference.toResolvedReference()

        // If we have a conflict between the expected type and the inferred type, we would like to set the inferred type on the expression,
        // so that we report INITIALIZER_TYPE_MISMATCH/RETURN_TYPE_MISMATCH.
        // This is required so that the IDE provides the correct quick fixes.
        if (syntheticCall.resultType !is ConeErrorType && resolvedCalleeReference is FirResolvedErrorReference) {
            val diagnostic = resolvedCalleeReference.diagnostic
            if (diagnostic is ConeConstraintSystemHasContradiction) {
                val candidate = diagnostic.candidate as Candidate
                val newSyntheticCallType =
                    session.typeContext.commonSuperTypeOrNull(candidate.argumentMapping.keys.map { it.expression.resolvedType })
                if (newSyntheticCallType != null && !newSyntheticCallType.hasError()) {
                    syntheticCall.replaceConeTypeOrNull(newSyntheticCallType)
                }
            }
        }

        syntheticCall.replaceCalleeReference(resolvedCalleeReference)

        return syntheticCall
    }

    private inline fun <reified D> transformSyntheticCallChildren(
        syntheticCall: D,
        data: ExpectedArgumentType?,
    ) where D : FirResolvable, D : FirExpression {
        val newExpectedType = data?.getExpectedType(syntheticCall) ?: syntheticCall.resolvedType
        val newData = newExpectedType.toExpectedType(syntheticCall.candidate()?.contextSensitiveResolutionReplacements)

        if (syntheticCall is FirTryExpression) {
            syntheticCall.transformCalleeReference(this, newData)
            syntheticCall.transformTryBlock(this, newData)
            syntheticCall.transformCatches(this, newData)
            return
        }

        syntheticCall.transformChildren(
            this,
            data = newData
        )
    }

    override fun transformLiteralExpression(
        literalExpression: FirLiteralExpression,
        data: ExpectedArgumentType?,
    ): FirStatement {
        val expectedType = data?.getExpectedType(literalExpression)
        if (expectedType is ConeIntegerConstantOperatorType) {
            return literalExpression
        }
        return literalExpression.transformSingle(integerOperatorApproximator, expectedType)
    }

    override fun transformIntegerLiteralOperatorCall(
        integerLiteralOperatorCall: FirIntegerLiteralOperatorCall,
        data: ExpectedArgumentType?,
    ): FirStatement {
        val expectedType = data?.getExpectedType(integerLiteralOperatorCall)
        if (expectedType is ConeIntegerConstantOperatorType) {
            return integerLiteralOperatorCall
        }
        return integerLiteralOperatorCall.transformSingle(integerOperatorApproximator, expectedType)
    }

    override fun transformArrayLiteral(arrayLiteral: FirArrayLiteral, data: ExpectedArgumentType?): FirStatement {
        if (arrayLiteral.isResolved) return arrayLiteral
        val expectedArrayType = data?.getExpectedType(arrayLiteral)
        val expectedArrayElementType = expectedArrayType?.arrayElementType()
        arrayLiteral.transformChildren(this, expectedArrayElementType?.toExpectedType(data.contextSensitiveResolutionReplacements))
        val arrayElementType =
            session.typeContext.commonSuperTypeOrNull(arrayLiteral.arguments.map { it.resolvedType })?.let {
                typeApproximator.approximateToSuperType(
                    it,
                    TypeApproximatorConfiguration.IntermediateApproximationToSupertypeAfterCompletionInK2
                )
                    ?: it
            } ?: expectedArrayElementType ?: session.builtinTypes.nullableAnyType.coneType
        arrayLiteral.resultType =
            arrayElementType.createArrayType(createPrimitiveArrayTypeIfPossible = expectedArrayType?.fullyExpandedType()?.isPrimitiveArray == true)
        return arrayLiteral
    }

    override fun transformVarargArgumentsExpression(
        varargArgumentsExpression: FirVarargArgumentsExpression,
        data: ExpectedArgumentType?,
    ): FirStatement {
        val expectedType = data?.getExpectedType(varargArgumentsExpression)
            ?.let { ExpectedArgumentType.ExpectedType(it, data.contextSensitiveResolutionReplacements) }
        varargArgumentsExpression.transformChildren(this, expectedType)
        return varargArgumentsExpression
    }

    // TODO: report warning with a checker and return true here only in case of errors, KT-59676
    private fun FirNamedReferenceWithCandidate.hasAdditionalResolutionErrors(): Boolean =
        candidate.system.errors.any { it is InferredEmptyIntersection }

    private fun FirNamedReferenceWithCandidate.toResolvedReference(): FirNamedReference {
        val errorDiagnostic = when {
            this is FirErrorReferenceWithCandidate -> this.diagnostic
            @OptIn(ApplicabilityDetail::class)
            !candidate.lowestApplicability.isSuccess -> ConeInapplicableCandidateError(candidate.lowestApplicability, candidate)
            !candidate.isSuccessful -> {
                require(candidate.system.hasContradiction) {
                    "Candidate is not successful, but system has no contradiction"
                }

                ConeConstraintSystemHasContradiction(candidate)
            }

            // NB: these additional errors might not lead to marking candidate unsuccessful because it may be a warning in FE 1.0
            // We consider those warnings as errors in FIR
            hasAdditionalResolutionErrors() -> ConeConstraintSystemHasContradiction(candidate)
            else -> null
        }

        return when (errorDiagnostic) {
            null -> buildResolvedNamedReference {
                source = this@toResolvedReference.source
                name = this@toResolvedReference.name
                resolvedSymbol = this@toResolvedReference.candidateSymbol
            }

            else -> toErrorReference(errorDiagnostic)
        }
    }

    override fun <E : FirElement> transformElement(
        element: E,
        data: ExpectedArgumentType?,
    ): E {
        // FirCallCompletionResultsWriterTransformer is expected to transform only a call and its expression arguments.
        // Though one of the arguments might be a lambda with arbitrary declarations inside, for a non-PCLA case all of them should be
        // processed in IndependentMode and fully completed.
        // In the case of PCLA, we use a dedicated FirTypeVariablesAfterPCLATransformer for adapting all the type variable usages inside the
        // lambda.
        //
        // This `if` is not only a fast-path avoiding unnecessary tree traversal, but also semantically necessary to avoid
        // traversal of some nodes that are not fully ready yet.
        // The main case currently is a delegated _var_ property inside PCLA, for which deliberately not resolve its setter until the
        // completion ends
        // (see usages of `isImplicitTypedProperty` at FirDeclarationsResolveTransformer.transformPropertyAccessorsWithDelegate).
        if (element is FirDeclaration) return element
        return super.transformElement(element, data)
    }
}

sealed class ExpectedArgumentType(
    val contextSensitiveResolutionReplacements: Map<FirElement, FirExpression>?,
) {
    class ArgumentsMap(
        val map: Map<FirElement, ConeKotlinType>,
        val lambdasReturnTypes: Map<FirAnonymousFunction, ConeKotlinType>,
        val samConversions: Map<FirElement, FirSamResolver.SamConversionInfo>,
        val argumentsWithFunctionKindConversion: Set<FirExpression>,
        val forErrorReference: Boolean,
        contextSensitiveResolutionReplacements: Map<FirElement, FirExpression>?,
    ) : ExpectedArgumentType(contextSensitiveResolutionReplacements)

    class ExpectedType(
        val type: ConeKotlinType,
        contextSensitiveResolutionReplacements: Map<FirElement, FirExpression>?,
    ) : ExpectedArgumentType(contextSensitiveResolutionReplacements)
}

private fun ExpectedArgumentType.getExpectedType(argument: FirElement): ConeKotlinType? = when (this) {
    is ExpectedArgumentType.ArgumentsMap -> map[argument]
    is ExpectedArgumentType.ExpectedType -> type
}

fun ConeKotlinType.toExpectedType(
    contextSensitiveResolutionReplacements: Map<FirElement, FirExpression>?,
): ExpectedArgumentType = ExpectedArgumentType.ExpectedType(this, contextSensitiveResolutionReplacements)

internal fun Candidate.doesResolutionResultOverrideOtherToPreserveCompatibility(): Boolean =
    ResolutionResultOverridesOtherToPreserveCompatibility in diagnostics

internal fun FirQualifiedAccessExpression.addNonFatalDiagnostics(candidate: Candidate) {
    val newNonFatalDiagnostics = mutableListOf<ConeDiagnostic>()

    if (candidate.doesResolutionResultOverrideOtherToPreserveCompatibility()) {
        newNonFatalDiagnostics += ConeResolutionResultOverridesOtherToPreserveCompatibility
    }

    for (diagnostic in candidate.diagnostics) {
        when (diagnostic) {
            is CallToDeprecatedOverrideOfHidden -> newNonFatalDiagnostics += ConeCallToDeprecatedOverrideOfHidden
            else -> null
        }
    }

    if (newNonFatalDiagnostics.isNotEmpty()) {
        replaceNonFatalDiagnostics(nonFatalDiagnostics + newNonFatalDiagnostics)
    }
}

private fun <K, V : Any> LinkedHashMap<out K, out V?>.filterValuesNotNull(): LinkedHashMap<K, V> {
    val result = LinkedHashMap<K, V>()
    for ((key, value) in this) {
        if (value != null) {
            result[key] = value
        }
    }
    return result
}

fun <V> LinkedHashMap<ConeResolutionAtom, V>.unwrapAtoms(): LinkedHashMap<FirExpression, V> {
    return mapKeysToLinkedMap { it.expression }
}

inline fun <K1, K2, V> LinkedHashMap<K1, V>.mapKeysToLinkedMap(transform: (K1) -> K2): LinkedHashMap<K2, V> {
    return mapKeysTo(LinkedHashMap()) { transform(it.key) }
}

private fun Collection<ConeResolutionAtom>.unwrapAtoms(): List<FirExpression> {
    return map { it.expression }
}
