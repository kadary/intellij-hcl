/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package org.intellij.plugins.hcl.terraform.il.codeinsight

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.LookupElementRenderer
import com.intellij.icons.AllIcons
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.patterns.PatternCondition
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import getNameElementUnquoted
import getPrevSiblingNonWhiteSpace
import org.intellij.plugins.hcl.psi.HCLBlock
import org.intellij.plugins.hcl.psi.HCLElement
import org.intellij.plugins.hcl.psi.HCLObject
import org.intellij.plugins.hcl.psi.HCLProperty
import org.intellij.plugins.hcl.terraform.config.codeinsight.ModelHelper
import org.intellij.plugins.hcl.terraform.config.codeinsight.ResourceBlockNameInsertHandler
import org.intellij.plugins.hcl.terraform.config.codeinsight.ResourcePropertyInsertHandler
import org.intellij.plugins.hcl.terraform.config.codeinsight.TerraformLookupElementRenderer
import org.intellij.plugins.hcl.terraform.config.model.*
import org.intellij.plugins.hcl.terraform.config.model.Function
import org.intellij.plugins.hcl.terraform.il.TILLanguage
import org.intellij.plugins.hcl.terraform.il.psi.*
import java.util.*

public class TILCompletionContributor : CompletionContributor() {
  init {
    extend(CompletionType.BASIC, METHOD_POSITION, MethodsCompletionProvider)
    extend(CompletionType.BASIC, PlatformPatterns.psiElement().withLanguage(TILLanguage)
        .withParent(ILVariable::class.java).withSuperParent(2, ILSE_FROM_KNOWN_SCOPE)
        , KnownScopeCompletionProvider)
    extend(CompletionType.BASIC, PlatformPatterns.psiElement().withLanguage(TILLanguage)
        .withParent(ILVariable::class.java).withSuperParent(2, ILSE_NOT_FROM_KNOWN_SCOPE)
        , SelectCompletionProvider)
  }


  companion object {
    @JvmField public val GLOBAL_SCOPES: SortedSet<String> = sortedSetOf("var", "path")
    @JvmField public val FUNCTIONS = ServiceManager.getService(TypeModelProvider::class.java).get().functions

    // For tests purposes
    @JvmField public val GLOBAL_AVAILABLE: SortedSet<String> = FUNCTIONS.map { it.name }.toArrayList().plus(GLOBAL_SCOPES).toSortedSet()


    private val PATH_REFERENCES = sortedSetOf("root", "module", "cwd")
    private val SCOPE_PROVIDERS = mapOf(
        Pair("var", VariableCompletionProvider),
        Pair("self", SelfCompletionProvider),
        Pair("path", PathCompletionProvider),
        Pair("count", CountCompletionProvider),
        Pair("module", ModuleCompletionProvider)
    )
    public val SCOPES = SCOPE_PROVIDERS.keys

    private val METHOD_POSITION = PlatformPatterns.psiElement().withLanguage(TILLanguage)
        .withParent(ILVariable::class.java)
        .andNot(PlatformPatterns.psiElement().withSuperParent(2, ILSelectExpression::class.java))

    public val ILSE_FROM_KNOWN_SCOPE = PlatformPatterns.psiElement(ILSelectExpression::class.java)
        .with(getScopeSelectPatternCondition(SCOPE_PROVIDERS.keys))
    public val ILSE_NOT_FROM_KNOWN_SCOPE = PlatformPatterns.psiElement(ILSelectExpression::class.java)
        .without(getScopeSelectPatternCondition(SCOPE_PROVIDERS.keys))


    private val LOG = Logger.getInstance(TILCompletionContributor::class.java)
    fun create(value: String): LookupElementBuilder {
      var builder = LookupElementBuilder.create(value)
      return builder
    }

    fun createScope(value: String): LookupElementBuilder {
      var builder = LookupElementBuilder.create(value)
      builder = builder.withInsertHandler(ScopeSelectInsertHandler)
      builder = builder.withRenderer(object : LookupElementRenderer<LookupElement?>() {
        override fun renderElement(element: LookupElement?, presentation: LookupElementPresentation?) {
          presentation?.icon = AllIcons.Nodes.Advice
          presentation?.itemText = element?.lookupString
        }
      })
      return builder
    }

    fun create(f: Function): LookupElementBuilder {
      var builder = LookupElementBuilder.create(f.name)
      builder = builder.withInsertHandler(FunctionInsertHandler)
      builder = builder.withRenderer(object : LookupElementRenderer<LookupElement?>() {
        override fun renderElement(element: LookupElement?, presentation: LookupElementPresentation?) {
          presentation?.icon = AllIcons.Nodes.Method // or Function
          presentation?.itemText = element?.lookupString
        }
      })
      return builder
    }

    fun create(value: PropertyOrBlockType, lookupString: String? = null): LookupElementBuilder {
      var builder = LookupElementBuilder.create(lookupString ?: value.name)
      builder = builder.withRenderer(TerraformLookupElementRenderer())
      return builder
    }
  }

  private object MethodsCompletionProvider : CompletionProvider<CompletionParameters>() {

    override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
      val position = parameters.position
      val parent = position.parent
      if (parent !is ILExpression) return
      val leftNWS = position.getPrevSiblingNonWhiteSpace()
      LOG.debug("TIL.MethodsCompletionProvider{position=$position, parent=$parent, left=${position.prevSibling}, lnws=$leftNWS}")
      result.addAllElements(FUNCTIONS.map { create(it) })
      result.addAllElements(GLOBAL_SCOPES.map { createScope(it) })
      if (getProvisionerResource(parent) != null) result.addElement(createScope("self"))
      if (getResource(parent) != null) result.addElement(createScope("count"))
    }
  }

  private abstract class SelectFromScopeCompletionProvider(val scope: String) : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext?, result: CompletionResultSet) {
      val position = parameters.position
      val parent = position.parent
      if (parent !is ILVariable) return
      val pp = parent.parent
      if (pp !is ILSelectExpression) return
      val from = pp.from
      if (from !is ILVariable) return
      if (scope != from.name) return
      LOG.debug("TIL.SelectFromScopeCompletionProvider($scope){position=$position, parent=$parent, pp=$pp}")
      doAddCompletions(parent, parameters, context, result)
    }

    abstract fun doAddCompletions(variable: ILVariable, parameters: CompletionParameters, context: ProcessingContext?, result: CompletionResultSet)
  }

  object KnownScopeCompletionProvider : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext?, result: CompletionResultSet) {
      val position = parameters.position
      val parent = position.parent
      if (parent !is ILVariable) return
      val pp = parent.parent
      if (pp !is ILSelectExpression) return
      val from = pp.from
      if (from !is ILVariable) return
      val provider = SCOPE_PROVIDERS[from.name] ?: return
      LOG.debug("TIL.SelectFromScopeCompletionProviderAny($from.name){position=$position, parent=$parent, pp=$pp}")
      provider.doAddCompletions(parent, parameters, context, result)
    }
  }

  private object VariableCompletionProvider : SelectFromScopeCompletionProvider("var") {
    override fun doAddCompletions(variable: ILVariable, parameters: CompletionParameters, context: ProcessingContext?, result: CompletionResultSet) {
      val variables: List<Variable> = getLocalDefinedVariables(variable);
      for (v in variables) {
        result.addElement(create(v.name))
      }
    }
  }

  private object SelfCompletionProvider : SelectFromScopeCompletionProvider("self") {
    override fun doAddCompletions(variable: ILVariable, parameters: CompletionParameters, context: ProcessingContext?, result: CompletionResultSet) {
      // For now 'self' allowed only for provisioners inside resources

      val resource = getProvisionerResource(variable) ?: return
      val properties = ModelHelper.getBlockProperties(resource)
      // TODO: Filter already defined or computed properties (?)
      // TODO: Add type filtration
      val set = properties.map { it.name }.toHashSet()
      val obj = resource.`object`
      if (obj != null) {
        set.addAll(obj.propertyList.map { it.name })
      }
      result.addAllElements(set.map { create(it) })
    }
  }

  private object PathCompletionProvider : SelectFromScopeCompletionProvider("path") {
    override fun doAddCompletions(variable: ILVariable, parameters: CompletionParameters, context: ProcessingContext?, result: CompletionResultSet) {
      result.addAllElements(PATH_REFERENCES.map { create(it) })
    }
  }

  private object CountCompletionProvider : SelectFromScopeCompletionProvider("count") {
    override fun doAddCompletions(variable: ILVariable, parameters: CompletionParameters, context: ProcessingContext?, result: CompletionResultSet) {
      getResource(variable) ?: return
      result.addElement(create("index"))
    }
  }

  private object ModuleCompletionProvider : SelectFromScopeCompletionProvider("module") {
    override fun doAddCompletions(variable: ILVariable, parameters: CompletionParameters, context: ProcessingContext?, result: CompletionResultSet) {
      val module = getTerraformModule(variable) ?: return
      val modules = module.getDefinedModules();
      for (m in modules) {
        val name = m.getNameElementUnquoted(1)
        if (name != null) result.addElement(create(name))
      }
    }
  }

  private object SelectCompletionProvider : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext?, result: CompletionResultSet) {
      val position = parameters.position

      val element = position.parent
      if (element !is ILVariable) return
      val host = InjectedLanguageManager.getInstance(element.project).getInjectionHost(element) ?: return
      if (host !is HCLElement) return

      val parent = element.parent
      if (parent !is ILSelectExpression) return

      val expression = getGoodLeftElement(parent, element);
      if (expression == null) {
        // v is leftmost, no idea what to do
        return;
      }
      val references = expression.references
      if (references.isNotEmpty()) {
        val resolved = references.map {
          if (it is HCLBlockNameReference) {
            it.block
          } else if (it is HCLBlockPropertyReference) {
            it.property
          } else {
            it.resolve()
          }
        }.filterNotNull()

        val found = ArrayList<LookupElement>()
        for (r in resolved) {
          when (r) {
            is HCLBlock -> {
              val properties = ModelHelper.getBlockProperties(r)
              found.addAll(properties.map { create(it) });
            }
            is HCLProperty -> {
              val value = r.value
              if (value is HCLObject) {
                found.addAll(value.propertyList.map { create(it.name) })
              }
            }
          }
        }
        if (!found.isEmpty()) {
          result.addAllElements(found)
        }
        return
      }

      if (expression is ILVariable) {
        val module = host.getTerraformModule()
        val resources = module.findResources(expression.name, null)
        if (resources.size == 0) return
        result.addAllElements(resources.map { it.getNameElementUnquoted(2) }.filterNotNull().map { create(it) })
        // TODO: support 'module.MODULE_NAME.OUTPUT_NAME' references (in that or another provider)
      }
    }

  }
}

public fun getTerraformModule(element: ILExpression): Module? {
  val host = InjectedLanguageManager.getInstance(element.project).getInjectionHost(element) ?: return null
  if (host !is HCLElement) return null
  val module = host.getTerraformModule()
  return module
}

public fun getLocalDefinedVariables(element: ILExpression): List<Variable> {
  return getTerraformModule(element)?.getAllVariables() ?: emptyList()
}

public fun getProvisionerResource(position: ILExpression): HCLBlock? {
  val host = InjectedLanguageManager.getInstance(position.project).getInjectionHost(position) ?: return null

  // For now 'self' allowed only for provisioners inside resources

  val provisioner = PsiTreeUtil.getParentOfType(host, HCLBlock::class.java) ?: return null
  if (provisioner.getNameElementUnquoted(0) != "provisioner") return null
  val resource = PsiTreeUtil.getParentOfType(provisioner, HCLBlock::class.java, true) ?: return null
  if (resource.getNameElementUnquoted(0) != "resource") return null
  return resource
}

public fun getResource(position: ILExpression): HCLBlock? {
  val host = InjectedLanguageManager.getInstance(position.project).getInjectionHost(position) ?: return null

  // For now 'self' allowed only for provisioners inside resources

  val resource = PsiTreeUtil.getParentOfType(host, HCLBlock::class.java, true) ?: return null
  if (resource.getNameElementUnquoted(0) != "resource") return null
  return resource
}

private fun getScopeSelectPatternCondition(scopes: Set<String>): PatternCondition<ILSelectExpression?> {
  return object : PatternCondition<ILSelectExpression?>("ScopeSelect($scopes)") {
    override fun accepts(t: ILSelectExpression?, context: ProcessingContext?): Boolean {
      val from = t?.from
      return from is ILVariable && from.name in scopes
    }
  }
}
