/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.sir.bridge.impl

import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertyGetterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySetterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSamConstructorSymbol
import org.jetbrains.kotlin.sir.*
import org.jetbrains.kotlin.sir.bridge.*
import org.jetbrains.kotlin.sir.mangler.mangledNameOrNull
import org.jetbrains.kotlin.sir.providers.source.InnerInitSource
import org.jetbrains.kotlin.sir.providers.source.kaSymbolOrNull
import org.jetbrains.kotlin.sir.providers.utils.KotlinRuntimeModule
import org.jetbrains.kotlin.sir.util.*
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstanceOrNull

private const val exportAnnotationFqName = "kotlin.native.internal.ExportedBridge"
private const val cinterop = "kotlinx.cinterop.*"
private const val convertBlockPtrToKotlinFunction = "kotlinx.cinterop.internal.convertBlockPtrToKotlinFunction"
private const val stdintHeader = "stdint.h"
private const val foundationHeader = "Foundation/Foundation.h"

private const val KOTLIN_BASIC_IDENTIFIER_HEADER = """_\p{Lu}\p{Ll}\p{Lt}\p{Lm}\p{Lo}"""
private const val KOTLIN_BASIC_IDENTIFIER_BODY = """\p{Nd}$KOTLIN_BASIC_IDENTIFIER_HEADER"""

private val kotlinQuotedIdentifierRegex =
    """^[^\r\n`]+\$""".toRegex()
private val kotlinQuotedIdentifierNonCompliantRegex =
    """[\r\n`]+""".toRegex()

private val kotlinBasicIdentifierRegex =
    "^[$KOTLIN_BASIC_IDENTIFIER_HEADER][$KOTLIN_BASIC_IDENTIFIER_BODY]*\$".toRegex()

private val cIdentifierRegex =
    "^[_a-zA-Z][_a-zA-Z0-9]*\$".toRegex()
private val cIdentifierNonCompliantRegex =
    """^[^_a-zA-Z][^_a-zA-Z0-9]*+|(?<!^)[^_a-zA-Z0-9]+""".toRegex()


internal class BridgeGeneratorImpl(private val typeNamer: SirTypeNamer) : BridgeGenerator {
    override fun generateBridges(request: BridgeRequest): List<GeneratedBridge> = when (request) {
        is FunctionBridgeRequest -> generateFunctionBridges(request)
        is TypeBindingBridgeRequest -> listOf(generateTypeBindingBridge(request))
    }

    private fun generateFunctionBridges(request: FunctionBridgeRequest) = buildList {
        fun argNames(descriptor: BridgeFunctionDescriptor) = descriptor.parameters.map { "__${it.name}".kotlinIdentifier }

        when (request.callable) {
            is SirFunction -> {
                add(
                    request.descriptor(typeNamer).createFunctionBridge {
                        val args = argNames(this)
                        when (val kaSymbol = request.callable.kaSymbolOrNull<KaFunctionSymbol>()) {
                            is KaPropertyGetterSymbol -> {
                                val expectedParameters = if (extensionReceiverParameter != null) 1 else 0
                                require(args.size == expectedParameters) { "Received an extension getter $name with ${args.size} parameters instead of a $expectedParameters, aborting" }
                                buildCall("")
                            }
                            is KaPropertySetterSymbol -> {
                                val expectedParameters = if (extensionReceiverParameter != null) 2 else 1
                                require(args.size == expectedParameters) { "Received an extension getter $name with ${args.size} parameters instead of a $expectedParameters, aborting" }
                                buildCall(" = ${args.last()}")
                            }
                            is KaNamedFunctionSymbol -> {
                                val actualArgs = if (extensionReceiverParameter != null) args.drop(1) else args
                                buildCall("(${actualArgs.joinToString()})")
                            }
                            is KaSamConstructorSymbol -> {
                                val actualArgs = args
                                buildCall("(${actualArgs.joinToString()})")
                            }
                            else -> error("Unexpected Kotlin symbol: ${kaSymbol}")
                        }
                    }
                )
            }
            is SirGetter -> {
                add(
                    request.descriptor(typeNamer).createFunctionBridge {
                        val args = argNames(this)
                        require(args.isEmpty()) { "Received a getter $name with ${args.size} parameters instead of no parameters, aborting" }
                        buildCall("")
                    }
                )
            }
            is SirSetter -> {
                add(
                    request.descriptor(typeNamer).createFunctionBridge {
                        val args = argNames(this)
                        require(args.size == 1) { "Received a setter $name with ${args.size} parameters instead of a single one, aborting" }
                        buildCall(" = ${args.single()}")
                    }
                )
            }
            is SirInit -> {
                add(
                    request.allocationDescriptor(typeNamer).createFunctionBridge {
                        val args = argNames(this)
                        "kotlin.native.internal.createUninitializedInstance<$name>(${args.joinToString()})"
                    }
                )
                if (request.callable.origin is InnerInitSource) {
                    add(
                        request.initializationDescriptor(typeNamer).createFunctionBridge {
                            val args = argNames(this)
                            require(kotlinFqName.size >= 2) {
                                "Expected >=2 kotlinFqName.size, but were ${kotlinFqName.size}: ${kotlinFqName.joinToString(",")}"
                            }
                            require(args.size >= 2) {
                                "Expected >=2 inner constructor arguments, but were ${args.size}: ${args.joinToString(",")}"
                            }
                            val outerClassName = kotlinFqName.dropLast(1).joinToString(".")
                            val innerClassName = kotlinFqName.last()
                            val innerConstructorArgs = args.drop(1).dropLast(1).joinToString(", ")
                            val innerConstructorCall = "(${args.last()} as $outerClassName).$innerClassName($innerConstructorArgs)"

                            "kotlin.native.internal.initInstance(${args.first()}, $innerConstructorCall)"
                        }
                    )
                } else {
                    add(
                        request.initializationDescriptor(typeNamer).createFunctionBridge {
                            val args = argNames(this)
                            "kotlin.native.internal.initInstance(${args.first()}, ${name}(${args.drop(1).joinToString()}))"
                        }
                    )
                }
            }
        }
    }

    override fun generateSirFunctionBody(request: FunctionBridgeRequest) = SirFunctionBody(buildList {
        when (request.callable) {
            is SirFunction, is SirGetter, is SirSetter -> {
                val descriptor = request.descriptor(typeNamer)
                val errorParameter = descriptor.errorParameter

                if (errorParameter != null) {
                    add("var ${errorParameter.name}: UnsafeMutableRawPointer? = nil")
                    add("let _result = ${descriptor.swiftInvoke(typeNamer)}")
                    val error = errorParameter.bridge.inSwiftSources.kotlinToSwift(typeNamer, errorParameter.name)
                    add("guard ${errorParameter.name} == nil else { throw KotlinError(wrapped: $error) }")
                    add("return ${descriptor.returnType.inSwiftSources.kotlinToSwift(typeNamer, "_result")}")
                } else {
                    add("return ${descriptor.swiftCall(typeNamer)}")
                }
            }
            is SirInit -> {
                val initDescriptor = request.initializationDescriptor(typeNamer)
                val errorParameter = initDescriptor.errorParameter

                (request.callable.parent as? SirNamedDeclaration)?.let { it ->
                    add("if Self.self != ${it.swiftFqName}.self { fatalError(\"Inheritance from exported Kotlin classes is not supported yet: \\(String(reflecting: Self.self)) inherits from ${it.swiftFqName} \") }")
                }
                add("let ${obj.name} = ${request.allocationDescriptor(typeNamer).swiftCall(typeNamer)}")
                add("super.init(__externalRCRefUnsafe: ${obj.name}, options: .asBoundBridge)")

                if (errorParameter != null) {
                    add("var ${errorParameter.name}: UnsafeMutableRawPointer? = nil")
                    add(initDescriptor.swiftCall(typeNamer))
                    val error = errorParameter.bridge.inSwiftSources.kotlinToSwift(typeNamer, errorParameter.name)
                    add("guard ${errorParameter.name} == .none else { throw KotlinError(wrapped: $error) }")
                } else {
                    add(initDescriptor.swiftCall(typeNamer))
                }

            }
        }
    })

    private fun generateTypeBindingBridge(request: TypeBindingBridgeRequest): TypeBindingBridge {
        val annotationName = "kotlin.native.internal.objc.BindClassToObjCName"
        val kotlinType = typeNamer.kotlinFqName(SirNominalType(request.sirTypeDeclaration))
        val swiftName = request.sirTypeDeclaration.let {
            it.attributes.firstIsInstanceOrNull<SirAttribute.ObjC>()?.name ?: it.mangledNameOrNull
        }
        requireNotNull(swiftName) {
            "Cannot mangle name for Swift class exported from `$kotlinType`"
        }
        return TypeBindingBridge(
            kotlinFileAnnotation = "$annotationName($kotlinType::class, \"$swiftName\")"
        )
    }
}

private class BridgeFunctionDescriptor(
    val baseBridgeName: String,
    val parameters: List<BridgeParameter>,
    val returnType: Bridge,
    val kotlinFqName: List<String>,
    val selfParameter: BridgeParameter?,
    val extensionReceiverParameter: BridgeParameter?,
    val errorParameter: BridgeParameter?,
    val typeNamer: SirTypeNamer,
) {
    val kotlinBridgeName = bridgeDeclarationName(baseBridgeName, parameters, typeNamer)
    val cBridgeName = kotlinBridgeName

    val allParameters
        get() = listOfNotNull(selfParameter) + parameters + listOfNotNull(errorParameter)

    val name
        get() = kotlinFqName.joinToString(separator = ".") { it.kotlinIdentifier }


    fun buildCall(args: String): String {
        return if (selfParameter == null) {
            if (extensionReceiverParameter == null) {
                "$name$args"
            } else {
                "__${extensionReceiverParameter.name}.$safeImportName$args"
            }
        } else {
            val memberName = kotlinFqName.last().kotlinIdentifier
            if (extensionReceiverParameter == null) {
                "__${selfParameter.name}.$memberName$args"
            } else {
                "__${selfParameter.name}.run { __${extensionReceiverParameter.name}.$memberName$args }"
            }
        }
    }
}

private fun FunctionBridgeRequest.descriptor(typeNamer: SirTypeNamer): BridgeFunctionDescriptor {
    require(callable !is SirInit) { "Use allocationDescriptor and initializationDescriptor instead" }
    return BridgeFunctionDescriptor(
        baseBridgeName = bridgeName,
        parameters = callable.bridgeParameters(),
        returnType = bridgeType(callable.returnType),
        kotlinFqName = fqName,
        selfParameter = if (callable.kind == SirCallableKind.INSTANCE_METHOD) {
            val selfType: SirType = when (val parent = callable.parent) {
                is SirNamedDeclaration -> SirNominalType(parent as SirNamedDeclaration)
                is SirVariable -> SirNominalType(parent.parent as SirNamedDeclaration)
                is SirExtension -> parent.extendedType
                else -> error("Only a member can have a self parameter")
            }
            BridgeParameter("self", bridgeType(selfType))
        } else null,
        extensionReceiverParameter = when (callable) {
            is SirFunction -> callable.extensionReceiverParameter?.let {
                BridgeParameter("receiver", bridgeType(it.type))
            }
            else -> null
        },
        errorParameter = callable.errorType.takeIf { it != SirType.never }?.let {
            BridgeParameter("_out_error", Bridge.AsOutError)
        },
        typeNamer = typeNamer,
    )
}

private val obj = BridgeParameter("__kt", bridgeType(SirNominalType(SirSwiftModule.unsafeMutableRawPointer)))

private fun FunctionBridgeRequest.allocationDescriptor(typeNamer: SirTypeNamer): BridgeFunctionDescriptor {
    require(callable is SirInit) { "Use descriptor instead" }
    return BridgeFunctionDescriptor(
        bridgeName + "_allocate",
        emptyList(),
        obj.bridge,
        fqName,
        null,
        null,
        null,
        typeNamer = typeNamer,
    )
}

private fun FunctionBridgeRequest.initializationDescriptor(typeNamer: SirTypeNamer): BridgeFunctionDescriptor {
    require(callable is SirInit) { "Use descriptor instead" }
    return BridgeFunctionDescriptor(
        bridgeName + "_initialize",
        listOf(obj) + callable.bridgeParameters(),
        bridgeType(callable.returnType),
        fqName,
        null,
        null,
        errorParameter = callable.errorType.takeIf { it != SirType.never }?.let {
            BridgeParameter("__error", Bridge.AsOutError)
        },
        typeNamer = typeNamer,
    )
}

// TODO: we need to mangle C name in more elegant way. KT-64970
// problems with this approach are:
// 1. there can be limit for declaration names in Clang compiler
// 1. this name will be UGLY in the debug session
private fun bridgeDeclarationName(bridgeName: String, parameterBridges: List<BridgeParameter>, typeNamer: SirTypeNamer): String {
    val nameSuffixForOverloadSimulation = parameterBridges.joinToString(separator = "_") {
        typeNamer.swiftFqName(it.bridge.swiftType)
            .replace(".", "_")
            .replace(",", "_")
            .replace("<", "_")
            .replace(">", "_")
    }
    val suffixString = if (parameterBridges.isNotEmpty()) "__TypesOfArguments__${nameSuffixForOverloadSimulation}__" else ""
    val result = "${bridgeName}${suffixString}".cIdentifier
    return result
}

private fun BridgeFunctionDescriptor.createKotlinBridge(
    typeNamer: SirTypeNamer,
    buildCallSite: BridgeFunctionDescriptor.() -> String,
) = buildList {
    add("@${exportAnnotationFqName.substringAfterLast('.')}(\"${cBridgeName}\")")
    add(
        "public fun $kotlinBridgeName(${
            allParameters.filter { it.isRenderable }.joinToString { "${it.name.kotlinIdentifier}: ${it.bridge.kotlinType.repr}" }
        }): ${returnType.kotlinType.repr} {"
    )
    val indent = "    "

    allParameters.forEach {
        val parameterName = "__${it.name}".kotlinIdentifier
        add("${indent}val $parameterName = ${it.bridge.inKotlinSources.swiftToKotlin(typeNamer, it.name.kotlinIdentifier)}")
    }
    val callSite = buildCallSite()
    if (returnType.swiftType.isVoid && errorParameter == null) {
        add("${indent}$callSite")
    } else {
        val resultName = "_result"
        if (errorParameter != null) {
            add(
                """
            try {
                val $resultName = $callSite
                return ${returnType.inKotlinSources.kotlinToSwift(typeNamer, resultName)}
            } catch (error: Throwable) {
                __${errorParameter.name}.value = StableRef.create(error).asCPointer()
                return ${returnType.kotlinType.defaultValue}
            }
            """.trimIndent().prependIndent(indent)
            )
        } else {
            add("${indent}val $resultName = $callSite")
            add("${indent}return ${returnType.inKotlinSources.kotlinToSwift(typeNamer, resultName)}")
        }
    }
    add("}")
}

private fun BridgeFunctionDescriptor.swiftInvoke(typeNamer: SirTypeNamer): String {
    val parameters = allParameters.filter { it.isRenderable }.joinToString {
        // We fix ugly `self` escaping here. This is the only place we'd otherwise need full support for swift's contextual keywords
        it.bridge.inSwiftSources.swiftToKotlin(typeNamer, it.name.takeIf { it == "self" } ?: it.name.swiftIdentifier)
    }
    return "$cBridgeName($parameters)"
}

private fun BridgeFunctionDescriptor.swiftCall(typeNamer: SirTypeNamer): String {
    return returnType.inSwiftSources.kotlinToSwift(typeNamer, swiftInvoke(typeNamer))
}

private fun BridgeFunctionDescriptor.cDeclaration() = buildString {
    append(
        returnType.cType.render(buildString {
            append(cBridgeName)
            append("(")
            allParameters.filter { it.isRenderable }.joinTo(this) { it.bridge.cType.render(it.name.cIdentifier) }
            append(')')
        })
    )
    if (returnType.swiftType.isNever) append(" __attribute((noreturn))")
    append(";")
}

private fun BridgeFunctionDescriptor.createFunctionBridge(kotlinCall: BridgeFunctionDescriptor.() -> String) =
    FunctionBridge(
        KotlinFunctionBridge(
            createKotlinBridge(typeNamer, kotlinCall),
            listOf(exportAnnotationFqName, cinterop, convertBlockPtrToKotlinFunction) + additionalImports()
        ),
        CFunctionBridge(listOf(cDeclaration()), listOf(foundationHeader, stdintHeader))
    )

private fun BridgeFunctionDescriptor.additionalImports(): List<String> {
    if (extensionReceiverParameter != null && selfParameter == null && kotlinFqName.size > 1) {
        return listOf("$name as $safeImportName")
    }
    return emptyList()
}

private fun SirCallable.bridgeParameters() = allParameters.mapIndexed { index, value -> bridgeParameter(value, index) }

private fun bridgeType(type: SirType): Bridge = when (type) {
    is SirNominalType -> bridgeNominalType(type)
    is SirExistentialType -> bridgeExistential(type)
    is SirFunctionalType -> Bridge.AsBlock(type)
    else -> error("Attempt to bridge unbridgeable type: $type.")
}

private fun bridgeExistential(type: SirExistentialType): Bridge = Bridge.AsExistential(
    swiftType = type,
    KotlinType.KotlinObject,
    CType.Object
)

private fun bridgeNominalType(type: SirNominalType): Bridge {
    fun bridgeAsNSCollectionElement(type: SirType): Bridge = when (val bridge = bridgeType(type)) {
        is Bridge.AsIs -> Bridge.AsNSNumber(bridge.swiftType)
        is Bridge.AsOptionalWrapper -> Bridge.AsObjCBridgedOptional(bridge.wrappedObject.swiftType)
        is Bridge.AsOptionalNothing -> Bridge.AsObjCBridgedOptional(bridge.swiftType)
        is Bridge.AsObject,
        is Bridge.AsExistential,
        is Bridge.AsOpaqueObject,
            -> Bridge.AsObjCBridged(bridge.swiftType, CType.id)
        else -> bridge
    }

    return when (val subtype = type.typeDeclaration) {
        SirSwiftModule.void -> Bridge.AsIs(type, KotlinType.Unit, CType.Void)

        SirSwiftModule.bool -> Bridge.AsIs(type, KotlinType.Boolean, CType.Bool)

        SirSwiftModule.int8 -> Bridge.AsIs(type, KotlinType.Byte, CType.Int8)
        SirSwiftModule.int16 -> Bridge.AsIs(type, KotlinType.Short, CType.Int16)
        SirSwiftModule.int32 -> Bridge.AsIs(type, KotlinType.Int, CType.Int32)
        SirSwiftModule.int64 -> Bridge.AsIs(type, KotlinType.Long, CType.Int64)

        SirSwiftModule.uint8 -> Bridge.AsIs(type, KotlinType.UByte, CType.UInt8)
        SirSwiftModule.uint16 -> Bridge.AsIs(type, KotlinType.UShort, CType.UInt16)
        SirSwiftModule.uint32 -> Bridge.AsIs(type, KotlinType.UInt, CType.UInt32)
        SirSwiftModule.uint64 -> Bridge.AsIs(type, KotlinType.ULong, CType.UInt64)

        SirSwiftModule.double -> Bridge.AsIs(type, KotlinType.Double, CType.Double)
        SirSwiftModule.float -> Bridge.AsIs(type, KotlinType.Float, CType.Float)

        SirSwiftModule.unsafeMutableRawPointer -> Bridge.AsOpaqueObject(type, KotlinType.KotlinObject, CType.Object)
        SirSwiftModule.never -> Bridge.AsOpaqueObject(type, KotlinType.KotlinObject, CType.Void)

        SirSwiftModule.string -> Bridge.AsObjCBridged(type, CType.NSString)

        SirSwiftModule.utf16CodeUnit -> Bridge.AsIs(type, KotlinType.Char, CType.UInt16)

        SirSwiftModule.optional -> when (val bridge = bridgeType(type.typeArguments.first())) {
            is Bridge.AsObject,
            is Bridge.AsObjCBridged,
            is Bridge.AsExistential,
            is Bridge.AsBlock,
                -> Bridge.AsOptionalWrapper(bridge)

            is Bridge.AsOpaqueObject -> {
                if (bridge.swiftType.isNever) {
                    Bridge.AsOptionalNothing
                } else {
                    error("Found Optional wrapping for OpaqueObject. That is impossible")
                }
            }

            is Bridge.AsIs,
                -> Bridge.AsOptionalWrapper(
                if (bridge.swiftType.isChar)
                    Bridge.OptionalChar(bridge.swiftType)
                else
                    Bridge.AsNSNumber(bridge.swiftType)
            )

            else -> error("Found Optional wrapping for $bridge. That is currently unsupported. See KT-66875")
        }

        SirSwiftModule.array -> Bridge.AsNSArray(type, bridgeAsNSCollectionElement(type.typeArguments.single()))
        SirSwiftModule.set -> Bridge.AsNSSet(type, bridgeAsNSCollectionElement(type.typeArguments.single()))
        SirSwiftModule.dictionary -> {
            val (key, value) = type.typeArguments
            Bridge.AsNSDictionary(
                type,
                bridgeAsNSCollectionElement(key),
                bridgeAsNSCollectionElement(value)
            )
        }

        is SirTypealias -> bridgeType(subtype.type)

        // TODO: Right now, we just assume everything nominal that we do not recognize is a class. We should make this decision looking at kotlin type?
        else -> if (type.typeDeclaration.parent is SirPlatformModule) {
            Bridge.AsNSObject(type)
        } else {
            Bridge.AsObject(type, KotlinType.KotlinObject, CType.Object)
        }
    }
}

private fun bridgeParameter(parameter: SirParameter, index: Int): BridgeParameter {
    val bridgeParameterName = parameter.name?.let(::createBridgeParameterName) ?: "_$index"
    val bridge = bridgeType(parameter.type)
    return BridgeParameter(
        name = bridgeParameterName,
        bridge = bridge
    )
}

private fun createBridgeParameterName(kotlinName: String): String {
    // TODO: Post-process because C has stricter naming conventions.
    return kotlinName
}

private data class BridgeParameter(
    val name: String,
    val bridge: Bridge,
) {
    var isRenderable: Boolean = bridge !is Bridge.AsOptionalNothing
}

private sealed class CType {
    abstract fun render(name: String): String

    val nullable: CType get() = ((this as? NullabilityAnnotated)?.wrapped ?: this).let { NullabilityAnnotated(it, Nullability.NULLABLE) }
    val nonnulll: CType get() = ((this as? NullabilityAnnotated)?.wrapped ?: this).let { NullabilityAnnotated(it, Nullability.NONNULL) }

    sealed class Predefined(private val repr: String) : CType() {
        override fun render(name: String): String = if (name.isBlank()) repr else "$repr $name"
    }

    enum class Nullability(val keyword: String) {
        NULLABLE("_Nullable"),
        NONNULL("_Nonnull"),
        NULL_UNSPECIFIED("_Null_unspecified"),
    }

    class NullabilityAnnotated(val wrapped: CType, val nullability: Nullability) : CType() {
        override fun render(name: String): String = wrapped.render(nullability.keyword + " " + (name.takeIf { name.isNotBlank() } ?: ""))
    }

    data object Void : Predefined("void")
    data object Bool : Predefined("_Bool")
    data object Int8 : Predefined("int8_t")
    data object Int16 : Predefined("int16_t")
    data object Int32 : Predefined("int32_t")
    data object Int64 : Predefined("int64_t")
    data object UInt8 : Predefined("uint8_t")
    data object UInt16 : Predefined("uint16_t")
    data object UInt32 : Predefined("uint32_t")
    data object UInt64 : Predefined("uint64_t")
    data object Float : Predefined("float")
    data object Double : Predefined("double")
    data object Object : Predefined("void *")
    data object OutObject : Predefined("void *_Nullable *")
    data object id : Predefined("id")
    data object NSString : Predefined("NSString *")
    data object NSNumber : Predefined("NSNumber *")
    data object NSObject : Predefined("id<NSObject>") // NSProxy and NSObject conforms to this

    sealed class Generic(base: String, vararg args: CType) : Predefined(
        repr = "$base<${args.joinToString(", ") { it.render("").trim() }}> *"
    )

    class NSArray(elem: CType) : Generic("NSArray", elem)
    class NSSet(elem: CType) : Generic("NSSet", elem)
    class NSDictionary(key: CType, value: CType) : Generic("NSDictionary", key, value)

    class BlockPointer(val parameters: List<CType>, val returnType: CType) : CType() {
        override fun render(name: String): String = returnType.render(buildString {
            append("(")
            append("^$name")
            append(")(")
            append(parameters.printCParametersForBlock())
            append(')')
        })

        private fun List<CType>.printCParametersForBlock(): String = if (isEmpty()) {
            "void" // A block declaration without a prototype is deprecated
        } else {
            joinToString { it.render("") }
        }
    }
}

private enum class KotlinType(val repr: String) {
    Unit("Unit"),

    Boolean("Boolean"),
    Char("Char"),

    Byte("Byte"),
    Short("Short"),
    Int("Int"),
    Long("Long"),

    UByte("UByte"),
    UShort("UShort"),
    UInt("UInt"),
    ULong("ULong"),

    Float("Float"),
    Double("Double"),

    KotlinObject("kotlin.native.internal.NativePtr"),

    PointerToKotlinObject("kotlinx.cinterop.COpaquePointerVar"),

    // id, +0
    ObjCObjectUnretained("kotlin.native.internal.NativePtr"),

    String("String"),
}

/**
 * Generate value conversions between Swift and Kotlin.
 */
private interface ValueConversion {
    fun swiftToKotlin(typeNamer: SirTypeNamer, valueExpression: String): String
    fun kotlinToSwift(typeNamer: SirTypeNamer, valueExpression: String): String
}

private interface NilRepresentable {
    fun renderNil(): String
}

private object IdentityValueConversion : ValueConversion {
    override fun swiftToKotlin(typeNamer: SirTypeNamer, valueExpression: String) = valueExpression
    override fun kotlinToSwift(typeNamer: SirTypeNamer, valueExpression: String) = valueExpression
}

private interface NilableIdentityValueConversion : Bridge.InSwiftSourcesConversion {
    override fun swiftToKotlin(typeNamer: SirTypeNamer, valueExpression: String) = valueExpression
    override fun kotlinToSwift(typeNamer: SirTypeNamer, valueExpression: String) = valueExpression
}

private fun String.mapSwift(temporalName: String = "it", transform: (String) -> String): String {
    val adapter = transform(temporalName).takeIf { it != temporalName }
    return this + (adapter?.let { ".map { $temporalName in $it }" } ?: "")
}

private sealed class Bridge(
    open val swiftType: SirType,
    val kotlinType: KotlinType,
    open val cType: CType,
) {
    class AsIs(swiftType: SirType, kotlinType: KotlinType, cType: CType) : Bridge(swiftType, kotlinType, cType) {
        override val inKotlinSources = IdentityValueConversion
        override val inSwiftSources = object : NilableIdentityValueConversion {
            override fun renderNil(): String = "nil"
        }
    }

    class AsObject(swiftType: SirType, kotlinType: KotlinType, cType: CType) : Bridge(swiftType, kotlinType, cType) {
        override val inKotlinSources = object : ValueConversion {
            // nulls are handled by AsOptionalWrapper, so safe to cast from nullable to non-nullable
            override fun swiftToKotlin(typeNamer: SirTypeNamer, valueExpression: String) =
                "kotlin.native.internal.ref.dereferenceExternalRCRef($valueExpression) as ${typeNamer.kotlinFqName(swiftType)}"

            override fun kotlinToSwift(typeNamer: SirTypeNamer, valueExpression: String) =
                "kotlin.native.internal.ref.createRetainedExternalRCRef($valueExpression)"
        }

        override val inSwiftSources = object : InSwiftSourcesConversion {
            override fun renderNil(): String = "nil"

            override fun swiftToKotlin(typeNamer: SirTypeNamer, valueExpression: String) = "${valueExpression}.__externalRCRef()"

            override fun kotlinToSwift(typeNamer: SirTypeNamer, valueExpression: String) =
                "${typeNamer.swiftFqName(swiftType)}.__createClassWrapper(externalRCRef: $valueExpression)"
        }
    }

    class AsExistential(swiftType: SirExistentialType, kotlinType: KotlinType, cType: CType) : Bridge(swiftType, kotlinType, cType) {
        override val inKotlinSources = object : ValueConversion {
            override fun swiftToKotlin(typeNamer: SirTypeNamer, valueExpression: String) =
                "kotlin.native.internal.ref.dereferenceExternalRCRef($valueExpression) as ${typeNamer.kotlinFqName(swiftType)}"

            override fun kotlinToSwift(typeNamer: SirTypeNamer, valueExpression: String) =
                "kotlin.native.internal.ref.createRetainedExternalRCRef($valueExpression)"
        }

        override val inSwiftSources = object : InSwiftSourcesConversion {
            override fun renderNil(): String = "nil"

            override fun swiftToKotlin(typeNamer: SirTypeNamer, valueExpression: String) = "${valueExpression}.__externalRCRef()"

            override fun kotlinToSwift(typeNamer: SirTypeNamer, valueExpression: String) =
                "${typeNamer.swiftFqName(SirNominalType(KotlinRuntimeModule.kotlinBase))}.__createProtocolWrapper(externalRCRef: $valueExpression) as! ${typeNamer.swiftFqName(swiftType)}"
        }
    }

    class AsOpaqueObject(swiftType: SirType, kotlinType: KotlinType, cType: CType) : Bridge(swiftType, kotlinType, cType) {
        override val inKotlinSources = object : ValueConversion {
            // nulls are handled by AsOptionalWrapper, so safe to cast from nullable to non-nullable
            override fun swiftToKotlin(typeNamer: SirTypeNamer, valueExpression: String) =
                "kotlin.native.internal.ref.dereferenceExternalRCRef($valueExpression)!!"

            override fun kotlinToSwift(typeNamer: SirTypeNamer, valueExpression: String) =
                "kotlin.native.internal.ref.createRetainedExternalRCRef($valueExpression)"
        }

        override val inSwiftSources = object : NilableIdentityValueConversion {
            override fun renderNil(): String = "nil"
        }
    }

    open class AsObjCBridged(
        swiftType: SirType,
        cType: CType,
    ) : Bridge(swiftType, KotlinType.ObjCObjectUnretained, cType) {
        override val inKotlinSources = object : ValueConversion {
            override fun swiftToKotlin(typeNamer: SirTypeNamer, valueExpression: String): String =
                "interpretObjCPointer<${typeNamer.kotlinFqName(swiftType)}>($valueExpression)"

            override fun kotlinToSwift(typeNamer: SirTypeNamer, valueExpression: String) =
                "$valueExpression.objcPtr()"
        }

        override val inSwiftSources: InSwiftSourcesConversion = object : NilableIdentityValueConversion {
            override fun renderNil(): String = "nil"
        }
    }

    /** To be used inside NS* collections. `null`s are wrapped as `NSNull`.  */
    open class AsObjCBridgedOptional(
        swiftType: SirType,
    ) : AsObjCBridged(swiftType, CType.id) {

        override val inSwiftSources = object : NilableIdentityValueConversion {
            override fun renderNil(): String = "NSNull()"

            override fun swiftToKotlin(typeNamer: SirTypeNamer, valueExpression: String): String {
                return "$valueExpression as NSObject? ?? ${renderNil()}"
            }

            override fun kotlinToSwift(typeNamer: SirTypeNamer, valueExpression: String): String = valueExpression
        }
    }

    open class AsNSNumber(
        swiftType: SirType,
    ) : AsObjCBridged(swiftType, CType.NSNumber) {
        override val inSwiftSources = object : NilableIdentityValueConversion {
            override fun renderNil(): String = super@AsNSNumber.inSwiftSources.renderNil()

            override fun swiftToKotlin(typeNamer: SirTypeNamer, valueExpression: String): String =
                "NSNumber(value: $valueExpression)"

            override fun kotlinToSwift(typeNamer: SirTypeNamer, valueExpression: String): String {
                require(swiftType is SirNominalType)
                val fromNSNumberValue = when (swiftType.typeDeclaration) {
                    SirSwiftModule.bool -> "boolValue"
                    SirSwiftModule.int8 -> "int8Value"
                    SirSwiftModule.int16 -> "int16Value"
                    SirSwiftModule.int32 -> "int32Value"
                    SirSwiftModule.int64 -> "int64Value"
                    SirSwiftModule.uint8 -> "uint8Value"
                    SirSwiftModule.uint16 -> "uint16Value"
                    SirSwiftModule.uint32 -> "uint32Value"
                    SirSwiftModule.uint64 -> "uint64Value"
                    SirSwiftModule.double -> "doubleValue"
                    SirSwiftModule.float -> "floatValue"

                    SirSwiftModule.utf16CodeUnit -> "uint16Value"

                    else -> error("Attempt to get ${swiftType.typeDeclaration} from NSNumber")
                }

                return "$valueExpression.$fromNSNumberValue"
            }
        }
    }

    class OptionalChar(swiftType: SirType) : AsNSNumber(swiftType) {
        init {
            require(swiftType.isChar)
        }

        override val inKotlinSources = object : ValueConversion by super.inKotlinSources {
            override fun kotlinToSwift(typeNamer: SirTypeNamer, valueExpression: String): String =
                super@OptionalChar.inKotlinSources.kotlinToSwift(typeNamer, "${valueExpression}?.code")
        }
    }

    class AsNSObject(
        swiftType: SirNominalType,
    ) : AsObjCBridged(swiftType, CType.NSObject) {
        override val inSwiftSources: InSwiftSourcesConversion = object : NilableIdentityValueConversion {
            override fun renderNil(): String = "nil"
            override fun kotlinToSwift(typeNamer: SirTypeNamer, valueExpression: String): String =
                "$valueExpression as! ${typeNamer.swiftFqName(swiftType)}"
        }
    }

    abstract class AsNSCollection(
        swiftType: SirNominalType,
        cType: CType,
    ) : AsObjCBridged(swiftType, cType) {
        abstract inner class InSwiftSources : InSwiftSourcesConversion {
            override fun renderNil(): String = super@AsNSCollection.inSwiftSources.renderNil()

            abstract override fun swiftToKotlin(typeNamer: SirTypeNamer, valueExpression: String): String

            override fun kotlinToSwift(typeNamer: SirTypeNamer, valueExpression: String): String {
                return "$valueExpression as! ${typeNamer.swiftFqName(swiftType)}"
            }
        }
    }

    class AsNSArray(swiftType: SirNominalType, elementBridge: Bridge) : AsNSCollection(swiftType, CType.NSArray(elementBridge.cType)) {
        override val inSwiftSources = object : InSwiftSources() {
            override fun swiftToKotlin(typeNamer: SirTypeNamer, valueExpression: String): String {
                return valueExpression.mapSwift { elementBridge.inSwiftSources.swiftToKotlin(typeNamer, it) }
            }
        }
    }

    class AsNSSet(swiftType: SirNominalType, elementBridge: Bridge) : AsNSCollection(swiftType, CType.NSSet(elementBridge.cType)) {
        override val inSwiftSources = object : InSwiftSources() {
            override fun swiftToKotlin(typeNamer: SirTypeNamer, valueExpression: String): String {
                val transformedElements = valueExpression.mapSwift { elementBridge.inSwiftSources.swiftToKotlin(typeNamer, it) }
                return if (transformedElements == valueExpression) valueExpression else "Set($transformedElements)"
            }
        }
    }

    class AsNSDictionary(swiftType: SirNominalType, val keyBridge: Bridge, val valueBridge: Bridge) :
        AsNSCollection(swiftType, CType.NSDictionary(keyBridge.cType, valueBridge.cType)) {

        override val inSwiftSources = object : InSwiftSources() {
            override fun swiftToKotlin(typeNamer: SirTypeNamer, valueExpression: String): String {
                val keyAdapter = keyBridge.inSwiftSources.swiftToKotlin(typeNamer, "key")
                val valueAdapter = valueBridge.inSwiftSources.swiftToKotlin(typeNamer, "value")
                return if (keyAdapter == "key" && valueAdapter == "value") {
                    valueExpression
                } else {
                    "Dictionary(uniqueKeysWithValues: $valueExpression.map { key, value in (" +
                            "${keyBridge.inSwiftSources.swiftToKotlin(typeNamer, "key")}, " +
                            "${valueBridge.inSwiftSources.swiftToKotlin(typeNamer, "value")} " +
                            ")})"
                }
            }
        }
    }

    data object AsOptionalNothing : Bridge(
        SirNominalType(SirSwiftModule.optional, listOf(SirNominalType(SirSwiftModule.never))),
        KotlinType.Unit,
        CType.Void
    ) {
        override val inKotlinSources = object : ValueConversion {
            override fun swiftToKotlin(typeNamer: SirTypeNamer, valueExpression: String) = "null"
            override fun kotlinToSwift(typeNamer: SirTypeNamer, valueExpression: String) = "Unit"
        }

        override val inSwiftSources = object : InSwiftSourcesConversion {
            override fun renderNil(): String = error("unrepresentable")
            override fun swiftToKotlin(typeNamer: SirTypeNamer, valueExpression: String) = renderNil()
            override fun kotlinToSwift(typeNamer: SirTypeNamer, valueExpression: String) =
                "{ ${valueExpression}; return nil; }()"
        }
    }

    class AsOptionalWrapper(
        val wrappedObject: Bridge,
    ) : Bridge(
        wrappedObject.swiftType.optional(),
        wrappedObject.kotlinType,
        wrappedObject.cType.nullable
    ) {

        override val inKotlinSources: ValueConversion
            get() = object : ValueConversion {
                override fun swiftToKotlin(typeNamer: SirTypeNamer, valueExpression: String): String {
                    return "if ($valueExpression == kotlin.native.internal.NativePtr.NULL) null else ${
                        wrappedObject.inKotlinSources.swiftToKotlin(typeNamer, valueExpression)
                    }"
                }

                override fun kotlinToSwift(typeNamer: SirTypeNamer, valueExpression: String): String {
                    return "if ($valueExpression == null) kotlin.native.internal.NativePtr.NULL else ${
                        wrappedObject.inKotlinSources.kotlinToSwift(typeNamer, valueExpression)
                    }"
                }
            }

        override val inSwiftSources: InSwiftSourcesConversion = object : InSwiftSourcesConversion {
            override fun swiftToKotlin(typeNamer: SirTypeNamer, valueExpression: String): String {
                require(wrappedObject is AsObjCBridged || wrappedObject is AsObject || wrappedObject is AsExistential || wrappedObject is AsBlock)
                return valueExpression.mapSwift { wrappedObject.inSwiftSources.swiftToKotlin(typeNamer, it) } +
                        " ?? ${wrappedObject.inSwiftSources.renderNil()}"
            }

            override fun kotlinToSwift(typeNamer: SirTypeNamer, valueExpression: String): String {
                return when (wrappedObject) {
                    is AsObjCBridged ->
                        valueExpression.mapSwift { wrappedObject.inSwiftSources.kotlinToSwift(typeNamer, it) }
                    is AsObject, is AsExistential, is AsBlock -> "{ switch $valueExpression { case ${wrappedObject.inSwiftSources.renderNil()}: .none; case let res: ${
                        wrappedObject.inSwiftSources.kotlinToSwift(typeNamer, "res")
                    }; } }()"
                    is AsIs,
                    is AsOpaqueObject,
                    is AsOutError,
                        -> TODO("not yet supported")

                    is AsOptionalWrapper, AsOptionalNothing -> error("there is not optional wrappers for optional")
                }
            }

            override fun renderNil(): String = error("we do not support wrapping optionals into optionals, as it is impossible in kotlin")
        }
    }

    class AsBlock(
        override val swiftType: SirFunctionalType,
        private val parameters: List<Bridge> = swiftType.parameterTypes.map(::bridgeType),
        private val returnType: Bridge = bridgeType(swiftType.returnType),
    ) : Bridge(
        swiftType = swiftType,
        kotlinType = KotlinType.KotlinObject,
        cType = CType.BlockPointer(
            parameters = parameters.map { it.cType },
            returnType = returnType.cType,
        )
    ) {
        override val cType: CType.BlockPointer
            get() = super.cType as? CType.BlockPointer
                ?: error("attempt to generate kotlin sources for handling closure fot a type that is not closure")

        private val kotlinFunctionTypeRendered = "(${parameters.joinToString { it.kotlinType.repr }})->${returnType.kotlinType.repr}"

        override val inKotlinSources: ValueConversion
            get() = object : ValueConversion {
                override fun swiftToKotlin(typeNamer: SirTypeNamer, valueExpression: String): String {
                    val argsInClosure = parameters
                        .mapIndexed { idx, el -> "arg${idx}" to el }.takeIf { it.isNotEmpty() }
                    val defineArgs = argsInClosure
                        ?.let { " ${it.joinToString { "${it.first}: ${typeNamer.kotlinFqName(it.second.swiftType)}" }} ->" }
                    val callArgs = argsInClosure
                        ?.let { it.joinToString { it.second.inKotlinSources.kotlinToSwift(typeNamer, it.first) } } ?: ""
                    return """run {    
                    |    val kotlinFun = convertBlockPtrToKotlinFunction<$kotlinFunctionTypeRendered>($valueExpression);
                    |    {${defineArgs ?: ""}
                    |        ${returnType.inKotlinSources.swiftToKotlin(typeNamer, "kotlinFun(${callArgs})")} 
                    |    }
                    |}""".replaceIndentByMargin("    ")
                }

                override fun kotlinToSwift(typeNamer: SirTypeNamer, valueExpression: String): String {
                    return """run {
                    |    val newClosure = { kotlin.native.internal.ref.createRetainedExternalRCRef(_result()).toLong() }
                    |    newClosure.objcPtr()
                    |}""".replaceIndentByMargin("    ")
                }
            }

        override val inSwiftSources: InSwiftSourcesConversion = object : InSwiftSourcesConversion {
            override fun swiftToKotlin(typeNamer: SirTypeNamer, valueExpression: String): String {
                val argsInClosure = parameters
                    .mapIndexed { idx, el -> "arg${idx}" to el }.takeIf { it.isNotEmpty() }
                val defineArgs = argsInClosure
                    ?.let { " ${it.joinToString { it.first }} in" } ?: ""
                val callArgs = argsInClosure
                    ?.let {
                        it.joinToString { param ->
                            param.second.inSwiftSources.kotlinToSwift(typeNamer, param.first)
                        }
                    } ?: ""
                return """{
                |    let originalBlock = $valueExpression
                |    return {$defineArgs ${"return ${returnType.inSwiftSources.swiftToKotlin(typeNamer, "originalBlock($callArgs)")}"} }
                |}()""".trimMargin()
            }

            override fun kotlinToSwift(typeNamer: SirTypeNamer, valueExpression: String): String {
                return """{
                |    let nativeBlock = $valueExpression
                |    return { nativeBlock() }
                |}()""".trimMargin()
            }

            override fun renderNil(): String = "nil"
        }
    }

    object AsOutError : Bridge(
        swiftType = SirType.never,
        kotlinType = KotlinType.PointerToKotlinObject,
        cType = CType.OutObject.nonnulll,
    ) {
        override val inKotlinSources: ValueConversion
            get() = IdentityValueConversion

        override val inSwiftSources: InSwiftSourcesConversion
            get() = object : InSwiftSourcesConversion {
                override fun swiftToKotlin(typeNamer: SirTypeNamer, valueExpression: String): String {
                    return "&$valueExpression"
                }

                override fun kotlinToSwift(typeNamer: SirTypeNamer, valueExpression: String): String {
                    return "${typeNamer.swiftFqName(SirNominalType(KotlinRuntimeModule.kotlinBase))}.__createClassWrapper(externalRCRef: $valueExpression)"
                }

                override fun renderNil(): String {
                    return "nil"
                }
            }
    }

    /**
     * [ValueConversion] to be used when generating Kotlin sources.
     */
    abstract val inKotlinSources: ValueConversion

    /**
     * [ValueConversion] to be used when generating Swift sources.
     */
    abstract val inSwiftSources: InSwiftSourcesConversion

    interface InSwiftSourcesConversion : ValueConversion, NilRepresentable
}

private val SirType.isChar: Boolean
    get() = this is SirNominalType && typeDeclaration == SirSwiftModule.utf16CodeUnit

private val KotlinType.defaultValue: String
    get() = when (this) {
        KotlinType.Unit -> "Unit"
        KotlinType.Boolean -> "false"
        KotlinType.Char -> "'\\u0000'"
        KotlinType.Byte,
        KotlinType.Short,
        KotlinType.Int,
        KotlinType.Long,
        KotlinType.UByte,
        KotlinType.UShort,
        KotlinType.UInt,
        KotlinType.ULong,
            -> "0"
        KotlinType.Float,
        KotlinType.Double,
            -> "0.0"
        KotlinType.PointerToKotlinObject -> error("PointerToKotlinObject shouldn't appear in return type position")
        KotlinType.KotlinObject,
        KotlinType.ObjCObjectUnretained, // This is semantically +0, so we're allowed to simply dismiss the pointer.
            -> "kotlin.native.internal.NativePtr.NULL"
        KotlinType.String -> ""
    }

private val String.cIdentifier: String
    get() = let {
        this.takeIf(cIdentifierRegex::matches) ?: this.replace(cIdentifierNonCompliantRegex) { match ->
            match.value.map {
                String.format("%02X", it.code)
            }.joinToString(separator = "", prefix = "U")
        }
    }.let { it.takeIf { !cKeywords.contains(it) } ?: "${it}_" }

private val cKeywords = setOf(
    "alignas", "alignof", "auto", "bool", "break", "case", "char", "const", "constexpr", "continue", "default", "do", "double", "else",
    "enum", "extern", "false", "float", "for", "goto", "id", "if", "inline", "int", "long", "nullptr", "register", "restrict", "return", "short",
    "signed", "sizeof", "static", "static_assert", "struct", "switch", "thread_local", "true", "typedef", "typeof", "typeof_unqual",
    "union", "unsigned", "void", "volatile", "while", "_Alignas", "_Alignof", "_Atomic", "_BitInt", "_Bool", "_Complex", "_Decimal128",
    "_Decimal32", "_Decimal64", "_Generic", "_Imaginary", "_Noreturn", "_Static_assert", "_Thread_local"
)

private val String.kotlinIdentifier: String
    get() = this.takeIf { kotlinBasicIdentifierRegex.matches(it) && !kotlinKeywords.contains(it) && it.any { it != '_' } }
        ?: (this.takeIf(kotlinQuotedIdentifierRegex::matches)
            ?: this.replace(kotlinQuotedIdentifierNonCompliantRegex) { "" }).let { "`$it`" }

private val kotlinKeywords = setOf(
    "return@", "continue@", "break@", "this@", "super@", "file", "field", "property", "get", "set", "receiver", "param", "setparam",
    "delegate", "package", "import", "class", "interface", "fun", "object", "val", "var", "typealias", "constructor", "by", "companion",
    "init", "this", "super", "typeof", "where", "if", "else", "when", "try", "catch", "finally", "for", "do", "while", "throw", "return",
    "continue", "break", "as", "is", "in", "!is", "!in", "out", "dynamic", "public", "private", "protected", "internal", "enum", "sealed",
    "annotation", "data", "inner", "tailrec", "operator", "inline", "infix", "external", "suspend", "override", "abstract", "final", "open",
    "const", "lateinit", "vararg", "noinline", "crossinline", "reified", "expect", "actual"
)

private val BridgeFunctionDescriptor.safeImportName: String
    get() = kotlinFqName.run { if (size <= 1) single() else joinToString("_") { it.replace("_", "__") } }
