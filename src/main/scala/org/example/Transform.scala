package org.example

import morphir.ir.{AccessControlled, Distribution, Module, Name, Package, Path, Type}
import morphir.ir.Documented.Documented
import morphir.ir.FQName.fqn

import scala.tools.nsc
import nsc.Global
import nsc.Phase
import nsc.plugins.{OutputFileWriter, Plugin, PluginComponent}
import morphir.sdk.Dict

import scala.annotation.tailrec
import scala.reflect.io.AbstractFile
import scala.tools.nsc.symtab.Flags


class Transform(val global: Global) extends Plugin {

    type MType[A] = morphir.ir.Type.Type[A]
    type MPath = morphir.ir.Path.Path

    import global._

    override val name = "Transform"
    override val description = "Transforms Scala to morphir"
    override val components: List[PluginComponent] = List[PluginComponent](Component)

    case class PluginOptions(disable: Boolean, outputPath: AbstractFile)

    var packageDef: Package.Definition[Unit, MType[Unit]] = synchronized(Package.emptyDefinition[Unit, morphir.ir.Type.Type[Unit]])
    var pluginOptions: PluginOptions = _
    val coreLibPackage: String = "org.library"
    val coreLibPath: String = """src\main\scala\org\library"""


    private object Component extends PluginComponent {

        override val global: Transform.this.global.type = Transform.this.global

        override val runsAfter: List[String] = List[String]("refchecks")
        override val phaseName: String = Transform.this.name


        override def newPhase(_prev: Phase) = new TransformPhase(_prev)

        class TransformPhase(prev: Phase) extends StdPhase(prev) {
            override def name = Transform.this.name

            override def cancelled(unit: global.CompilationUnit): Boolean = {
                // can ignore processing the units for the env, and their implementations
                unit.isJava || unit.source.path.contains(coreLibPath)
            }

            override def apply(unit: global.CompilationUnit): Unit = {
                traverser.traverse(unit.body)
            }
        }
    }


    override def init(options: List[String], error: String => Unit): Boolean = {
        pluginOptions = helpers.processOptions(options)
        !pluginOptions.disable
    }

    override val optionsHelp: Option[String] = Some(
        """-P:MorphirFE:outputPath=<path>   Set the path for the plugin output.
          |-P:MorphirFE:disable             Disable morphir-ir generation.
          |""".stripMargin
    )

    override def writeAdditionalOutputs(writer: OutputFileWriter): Unit = {
        // get the output path, TODO should come from an option
        val outputDirectory = pluginOptions.outputPath

        // create and convert a distribution into the morphir ir JSON
        val distro = Distribution.Distribution.Library(Path.fromString("Project"), Dict.empty, packageDef)
        val encodedPackageDef = morphir.FormatVersionCodecs.encodeDistributionVersion(distro)


        val jsonBytes = encodedPackageDef.toString.getBytes("UTF-8")
        writer.writeFile("morphir-ir.json", jsonBytes, outputDirectory)

        super.writeAdditionalOutputs(writer)
    }


    object traverser extends Traverser {
        override def traverse(tree: global.Tree): Unit = {
            tree match {
                case classDef: ClassDef if !inLibrary(classDef) && isSubTypeOfEnv(classDef) =>
                    toMorphirModule(classDef)
                case _ => super.traverse(tree)
            }
        }
    }


    def inLibrary(classDef: Transform.this.global.ClassDef): Boolean =
        classDef.symbol.fullName.startsWith(coreLibPackage)


    def isSubTypeOfEnv(classDef: Transform.this.global.ClassDef): Boolean =
        classDef.impl.parents.exists(_.symbol.name == TypeName("Env"))


    object classTraverser extends Traverser {

        // name for the module currently being processed.
        var moduleName: MPath = Nil
        var moduleDef: Module.Definition[Unit, MType[Unit]] = _

        def emptyModule: AccessControlled.AccessControlled[Module.Definition[Unit, MType[Unit]]] =
            AccessControlled.public(Module.emptyDefinition)

        def resetModuleDef(): Unit =
            moduleDef = morphir.ir.Module.emptyDefinition[Unit, MType[Unit]]

        def doTraverse(tree: global.Tree, modName: MPath): Unit = {
            moduleName = modName
            resetModuleDef()
            traverse(tree)
        }


        override def traverse(tree: global.Tree): Unit = {
            // ignore anything in the core library
            if (tree.symbol != null && !tree.symbol.fullName.startsWith(coreLibPackage)) {
                println(("mapping tree", tree.symbol.fullName))
                tree match {

                    // only case class class-definitions are allowed in a module
                    // case classes are interpreted as records in morphir
                    case cd@ClassDef(mods, name, tparams, impl) if !cd.symbol.fullName.startsWith(coreLibPackage) =>

                        // error out if it's not a case class
                        if (!mods.hasFlag(Flags.CASE)) reporter.error(cd.pos, "Non case class structures are not allowed here.")

                        // error out if the case class has a type parameter
                        if (tparams.nonEmpty)
                            reporter.error(
                                tparams.head.pos,
                                "Case classes should not have type parameters, please use specific types."
                            )


                        // fields of the case class
                        val fieldDefs: List[global.ValDef] = cd.impl.body.collectFirst {
                            case constr: DefDef if constr.name == termNames.CONSTRUCTOR => constr.vparamss.flatten
                        }.getOrElse(Nil)

                        val recName = Name.fromString(name.toString())
                        val rec = morphir.ir.Type.record({})(helpers.collectFields(fieldDefs))

                        val tpeDef: AccessControlled.AccessControlled[Documented[morphir.ir.Type.Definition[Unit]]] =
                            AccessControlled.public(Documented("", morphir.ir.Type.TypeAliasDefinition(Nil, rec)))
                        val accessCntrlModuleDef: AccessControlled.AccessControlled[Module.Definition[Unit, MType[Unit]]] =
                            packageDef.modules.getOrElse(moduleName, emptyModule)
                        val access = accessCntrlModuleDef.access
                        val moduleDef = accessCntrlModuleDef.value

                        val updatedModuleDef = moduleDef.copy(types = Dict.insert(recName)(tpeDef)(moduleDef.types))

                        // insert it into the package
                        packageDef = packageDef.copy(modules =
                            Dict.insert(moduleName)(AccessControlled.AccessControlled(access, updatedModuleDef))(packageDef.modules)
                        )

                        // continue traversing
                        super.traverse(impl)
                    case _ => {
                        super.traverse(tree)
                    }
                }
            }
        }
    }


    def toMorphirModule(classDef: Transform.this.global.ClassDef): Unit = {
        // Each Class Def (trait, class, etc) that extends Env is treated as a module in Morphir
        val moduleDef = AccessControlled.public(morphir.ir.Module.emptyDefinition[Unit, MType[Unit]])
        val moduleName: morphir.ir.Path.Path = classDef.symbol.ownerChain
          .filter(!_.isRoot)
          .map(sym => Name.fromString(sym.name.toString()))
          .reverse

        packageDef = Package.Definition(Dict.insert(moduleName)(moduleDef)(packageDef.modules))

        // traverse the contents of the class
        classTraverser.doTraverse(classDef.impl, moduleName)
    }


    object helpers {

        import morphir.ir.Type

        def isMutable(valDef: ValDef): Boolean = {
            (valDef.mods.flags & Flags.MUTABLE) != 0
        }

        def processOptions(ops: List[String]): PluginOptions = {
            PluginOptions(
                disable = ops.contains("disable"),
                outputPath = ops.find(_.contains("outputPath"))
                  .map(_.substring("outputPath".length))
                  .map(AbstractFile.getFile)
                  .getOrElse(global.settings.outputDirs.getSingleOutput.get)
            )
        }

        @tailrec
        def collectFields(fields: Seq[global.Tree], collected: List[morphir.ir.Type.Field[Unit]] = Nil): List[morphir.ir.Type.Field[Unit]] =
            fields match {

                case (vd@ValDef(mods, name, tpt, rhs)) :: rest =>
                    // no `var` params allowed
                    if (vd.mods.hasFlag(Flags.MUTABLE))
                        reporter.error(mods.positions.getOrElse(Flags.MUTABLE, vd.pos), "Mutable values not allowed.")

                    // should have no default values
                    if (rhs.nonEmpty) reporter.error(rhs.pos, "Default values not allowed")

                    val n = Name.fromString(name.toString())
                    val tpe = mapType(vd.symbol.tpe)
                    collectFields(rest, Type.Field(n, tpe) +: collected)

                case Nil => collected
            }

        def mapType(tpe: global.Type): MType[Unit] = {
            tpe match {
                /* CORE LIBRARY TYPES */
                case coreType if coreType.typeSymbol.fullName.startsWith("org.library.Env") =>

                    coreType match {
                        case TypeRef(_, sym, Nil) if sym.fullName == "org.library.Env.String" =>
                            morphir.ir.Type.Reference({}, fqn("Morphir.IR")("String")("String"), Nil)

                        case TypeRef(_, sym, Nil) if sym.fullName == "org.library.Env.Bool" =>
                            morphir.ir.Type.Reference({}, fqn("Morphir.IR")("Basics")("Bool"), Nil)
//
//                        case localRef @ TypeRef(_, sym, Nil) =>
//                            morphir.ir.Type.Reference({}, fqn("Morphir.IR")("String")("String"), Nil)

                        case t =>
                            println(showRaw(t))
//                            global.reporter.error(coreType.typeSymbol., s"Unknown type ${coreType.typeSymbol.fullName}")
                            morphir.ir.Type.Unit({})

                    }


                /* NON-CORE TYPES
                * Non-core types should not have type params.
                * */

                // reference to a locally defined type
                case ref@TypeRef(_, sym, Nil) =>
                    println(("ref sym", ref.parents))
                    morphir.ir.Type.Unit({})


                // type alias
                case alias: AliasTypeRef =>
                    println(("alias ref", alias.parents))
                    morphir.ir.Type.Unit({})


                // non-supported type
                case _ =>
                    global.reporter.error(tpe.typeSymbol.pos, "Unsupported type. Please use one of the supported types.")
                    morphir.ir.Type.Unit({})

            }
        }
    }

    def reportError(tree: Tree, message: String): Unit = {
        reporter.error(tree.pos, message)
    }
}
