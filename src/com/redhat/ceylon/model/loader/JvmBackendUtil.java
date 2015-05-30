package com.redhat.ceylon.model.loader;

import static com.redhat.ceylon.model.typechecker.model.Util.getSignature;

import java.util.List;
import java.util.Map;

import com.redhat.ceylon.common.BooleanUtil;
import com.redhat.ceylon.model.loader.mirror.AnnotatedMirror;
import com.redhat.ceylon.model.loader.mirror.AnnotationMirror;
import com.redhat.ceylon.model.loader.mirror.ClassMirror;
import com.redhat.ceylon.model.loader.model.LazyClass;
import com.redhat.ceylon.model.loader.model.LazyInterface;
import com.redhat.ceylon.model.typechecker.model.Class;
import com.redhat.ceylon.model.typechecker.model.ClassAlias;
import com.redhat.ceylon.model.typechecker.model.ClassOrInterface;
import com.redhat.ceylon.model.typechecker.model.Constructor;
import com.redhat.ceylon.model.typechecker.model.Declaration;
import com.redhat.ceylon.model.typechecker.model.Functional;
import com.redhat.ceylon.model.typechecker.model.Interface;
import com.redhat.ceylon.model.typechecker.model.Method;
import com.redhat.ceylon.model.typechecker.model.MethodOrValue;
import com.redhat.ceylon.model.typechecker.model.ModelUtil;
import com.redhat.ceylon.model.typechecker.model.Parameter;
import com.redhat.ceylon.model.typechecker.model.Type;
import com.redhat.ceylon.model.typechecker.model.Scope;
import com.redhat.ceylon.model.typechecker.model.Specification;
import com.redhat.ceylon.model.typechecker.model.TypeDeclaration;
import com.redhat.ceylon.model.typechecker.model.TypedDeclaration;
import com.redhat.ceylon.model.typechecker.model.Value;

public class JvmBackendUtil {
    public static boolean isInitialLowerCase(String name) {
        return !name.isEmpty() && isLowerCase(name.codePointAt(0));
    }

    public static boolean isLowerCase(int codepoint) {
        return Character.isLowerCase(codepoint) || codepoint == '_';
    }

    public static String getName(List<String> parts){
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            sb.append(parts.get(i));
            if (i < parts.size() - 1) {
                sb.append('.');
            }
        }
        return sb.toString();
    }

    public static String getMirrorName(AnnotatedMirror mirror) {
        String name;
        AnnotationMirror annot = mirror.getAnnotation(AbstractModelLoader.CEYLON_NAME_ANNOTATION);
        if (annot != null) {
            name = (String)annot.getValue();
        } else {
            name = mirror.getName();
            name = name.isEmpty() ? name : NamingBase.stripLeadingDollar(name);
            if (mirror instanceof ClassMirror
                    && JvmBackendUtil.isInitialLowerCase(name)
                    && name.endsWith("_")
                    && mirror.getAnnotation(AbstractModelLoader.CEYLON_CEYLON_ANNOTATION) != null) {
                name = name.substring(0, name.length()-1);
            }
        }
        return name;
    }

    public static boolean isSubPackage(String moduleName, String pkgName) {
        return pkgName.equals(moduleName)
                || pkgName.startsWith(moduleName+".");
    }

    /**
     * Removes the given character from the given string. More efficient than using String.replace
     * which uses regexes.
     */
    public static String removeChar(char c, String string) {
        int nextChar = string.indexOf(c);
        if(nextChar == -1)
            return string;
        int start = 0;
        StringBuilder ret = new StringBuilder(string.length()-1);// we remove at least one
        while(nextChar != -1){
            ret.append(string, start, nextChar);
            start = nextChar+1;
            nextChar = string.indexOf(c, start);
        }
        // don't forget the end part
        ret.append(string, start, string.length());
        return ret.toString();
    }

    public static String strip(String name, boolean isCeylon, boolean isShared) {
        String stripped = NamingBase.stripLeadingDollar(name);
        String privSuffix = NamingBase.Suffix.$priv$.name();
        if(isCeylon && !isShared && name.endsWith(privSuffix))
            return stripped.substring(0, stripped.length() - privSuffix.length());
        return stripped;
    }

    /**
     * Determines whether the declaration is a non-transient non-parameter value 
     * (not a getter)
     * @param decl The declaration
     * @return true if the declaration is a value
     */
    public static boolean isValue(Declaration decl) {
        return (decl instanceof Value)
                && !((Value)decl).isParameter()
                && !((Value)decl).isTransient();
    }

    /**
     * Determines whether the declaration's is a method
     * @param decl The declaration
     * @return true if the declaration is a method
     */
    public static boolean isMethod(Declaration decl) {
        return (decl instanceof Method)
                && !((Method)decl).isParameter();
    }

    public static boolean isCeylon(TypeDeclaration declaration) {
        if(declaration instanceof LazyClass){
            return ((LazyClass)declaration).isCeylon();
        }
        if(declaration instanceof LazyInterface){
            return ((LazyInterface)declaration).isCeylon();
        }
        // if it's not one of those it must be from source (Ceylon)
        return true;
    }

    public static Declaration getTopmostRefinedDeclaration(Declaration decl){
        return getTopmostRefinedDeclaration(decl, null);
    }

    public static Declaration getTopmostRefinedDeclaration(Declaration decl, Map<Method, Method> methodOverrides){
        if (decl instanceof MethodOrValue
                && ((MethodOrValue)decl).isParameter()
                && decl.getContainer() instanceof Class) {
            // Parameters in a refined class are not considered refinements themselves
            // We have in find the refined attribute
            Class c = (Class)decl.getContainer();
            boolean isAlias = c.isAlias();
            boolean isActual = c.isActual();
            // aliases and actual classes actually do refine their extended type parameters so the same rules apply wrt
            // boxing and stuff
            if (isAlias || isActual) {
                Functional ctor = null;
                int index = c.getParameterList().getParameters().indexOf(findParamForDecl(((TypedDeclaration)decl)));
                // ATM we only consider aliases if we're looking at aliases, and same for actual, not mixing the two.
                // Note(Stef): not entirely sure about that one, what about aliases of actual classes?
                while ((isAlias && c.isAlias())
                        || (isActual && c.isActual())) {
                    ctor = (isAlias && c.isAlias()) ? (Functional)((ClassAlias)c).getConstructor() : c;
                    Type et = c.getExtendedType();
                    c = et!=null && et.isClass() ? (Class)et.getDeclaration() : null;
                    // handle compile errors
                    if(c == null)
                        return null;
                }
                if (isActual) {
                    ctor = c;
                }
                // be safe
                if(ctor == null 
                        || ctor.getParameterLists() == null
                        || ctor.getParameterLists().isEmpty()
                        || ctor.getParameterLists().get(0) == null
                        || ctor.getParameterLists().get(0).getParameters() == null
                        || ctor.getParameterLists().get(0).getParameters().size() <= index)
                    return null;
                decl = ctor.getParameterLists().get(0).getParameters().get(index).getModel();
            }
            if (decl.isShared()) {
                Declaration refinedDecl = c.getRefinedMember(decl.getName(), getSignature(decl), false);//?? ellipsis=false??
                if(refinedDecl != null && !ModelUtil.equal(refinedDecl, decl)) {
                    return getTopmostRefinedDeclaration(refinedDecl, methodOverrides);
                }
            }
            return decl;
        } else if(decl instanceof MethodOrValue
                && ((MethodOrValue)decl).isParameter() // a parameter
                && ((decl.getContainer() instanceof Method && !(((Method)decl.getContainer()).isParameter())) // that's not parameter of a functional parameter 
                        || decl.getContainer() instanceof Specification // or is a parameter in a specification
                        || (decl.getContainer() instanceof Method  
                            && ((Method)decl.getContainer()).isParameter() 
                            && createMethod((Method)decl.getContainer())))) {// or is a class functional parameter
            // Parameters in a refined method are not considered refinements themselves
            // so we have to look up the corresponding parameter in the container's refined declaration
            Functional func = (Functional)getParameterized((MethodOrValue)decl);
            if(func == null)
                return decl;
            Declaration kk = getTopmostRefinedDeclaration((Declaration)func, methodOverrides);
            // error recovery
            if(kk instanceof Functional == false)
                return decl;
            Functional refinedFunc = (Functional) kk;
            // shortcut if the functional doesn't override anything
            if (ModelUtil.equal((Declaration)refinedFunc, (Declaration)func)) {
                return decl;
            }
            if (func.getParameterLists().size() != refinedFunc.getParameterLists().size()) {
                // invalid input
                return decl;
            }
            for (int ii = 0; ii < func.getParameterLists().size(); ii++) {
                if (func.getParameterLists().get(ii).getParameters().size() != refinedFunc.getParameterLists().get(ii).getParameters().size()) {
                    // invalid input
                    return decl;
                }
                // find the index of the parameter in the declaration
                int index = 0;
                for (Parameter px : func.getParameterLists().get(ii).getParameters()) {
                    if (px.getModel() == null || px.getModel().equals(decl)) {
                        // And return the corresponding parameter from the refined declaration
                        return refinedFunc.getParameterLists().get(ii).getParameters().get(index).getModel();
                    }
                    index++;
                }
                continue;
            }
        }else if(methodOverrides != null
                && decl instanceof Method
                && ModelUtil.equal(decl.getRefinedDeclaration(), decl)
                && decl.getContainer() instanceof Specification
                && ((Specification)decl.getContainer()).getDeclaration() instanceof Method
                && ((Method) ((Specification)decl.getContainer()).getDeclaration()).isShortcutRefinement()
                // we do all the previous ones first because they are likely to filter out false positives cheaper than the
                // hash lookup we do next to make sure it is really one of those cases
                && methodOverrides.containsKey(decl)){
            // special case for class X() extends T(){ m = function() => e; } which we inline
            decl = methodOverrides.get(decl);
        }
        Declaration refinedDecl = decl.getRefinedDeclaration();
        if(refinedDecl != null && !ModelUtil.equal(refinedDecl, decl))
            return getTopmostRefinedDeclaration(refinedDecl);
        return decl;
    }

    public static Parameter findParamForDecl(TypedDeclaration decl) {
        String attrName = decl.getName();
        return findParamForDecl(attrName, decl);
    }
    
    public static Parameter findParamForDecl(String attrName, TypedDeclaration decl) {
        Parameter result = null;
        if (decl.getContainer() instanceof Functional) {
            Functional f = (Functional)decl.getContainer();
            result = f.getParameter(attrName);
        }
        return result;
    }

    public static Declaration getParameterized(MethodOrValue methodOrValue) {
        if (!methodOrValue.isParameter()) {
            return null;
        }
        Scope scope = methodOrValue.getContainer();
        if (scope instanceof Specification) {
            return ((Specification)scope).getDeclaration();
        } else if (scope instanceof Declaration) {
            return (Declaration)scope;
        } 
        return null;
    }

    public static boolean createMethod(MethodOrValue model) {
        return model instanceof Method
                && model.isParameter()
                && model.isClassMember()
                && (model.isShared() || model.isCaptured());
    }

    public static boolean supportsReified(Declaration declaration){
        if(declaration instanceof ClassOrInterface){
            // Java constructors don't support reified type arguments
            return isCeylon((TypeDeclaration) declaration);
        }else if(declaration instanceof Method){
            if (((Method)declaration).isParameter()) {
                // those can never be parameterised
                return false;
            }
            if(declaration.isToplevel())
                return true;
            // Java methods don't support reified type arguments
            Method m = (Method) getTopmostRefinedDeclaration(declaration);
            // See what its container is
            ClassOrInterface container = ModelUtil.getClassOrInterfaceContainer(m);
            // a method which is not a toplevel and is not a class method, must be a method within method and
            // that must be Ceylon so it supports it
            if(container == null)
                return true;
            return supportsReified(container);
        }else if(declaration instanceof Constructor){
            // Java constructors don't support reified type arguments
            return isCeylon((Constructor) declaration);
        }else{
            return false;
        }
    }
    
    public static boolean isCompanionClassNeeded(TypeDeclaration decl) {
        return decl instanceof Interface 
                && BooleanUtil.isNotFalse(((Interface)decl).isCompanionClassNeeded());
    }

    /**
     * Determines whether the given attribute should be accessed and assigned 
     * via a {@code VariableBox}
     */
    public static boolean isBoxedVariable(TypedDeclaration attr) {
        return ModelUtil.isNonTransientValue(attr)
                && ModelUtil.isLocalNotInitializer(attr)
                && ((attr.isVariable() && attr.isCaptured())
                        // self-captured objects must also be boxed like variables
                        || attr.isSelfCaptured());
    }

    public static boolean isJavaArray(TypeDeclaration decl) {
        if(decl instanceof Class == false)
            return false;
        Class c = (Class) decl;
        String name = c.getQualifiedNameString();
        return name.equals("java.lang::ObjectArray")
                || name.equals("java.lang::ByteArray")
                || name.equals("java.lang::ShortArray")
                || name.equals("java.lang::IntArray")
                || name.equals("java.lang::LongArray")
                || name.equals("java.lang::FloatArray")
                || name.equals("java.lang::DoubleArray")
                || name.equals("java.lang::BooleanArray")
                || name.equals("java.lang::CharArray");
    }
}
