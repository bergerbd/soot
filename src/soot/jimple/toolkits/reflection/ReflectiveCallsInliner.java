/* Soot - a J*va Optimization Framework
 * Copyright (C) 2010 Eric Bodden
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the
 * Free Software Foundation, Inc., 59 Temple Place - Suite 330,
 * Boston, MA 02111-1307, USA.
 */

package soot.jimple.toolkits.reflection;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import soot.Body;
import soot.BooleanType;
import soot.Local;
import soot.PatchingChain;
import soot.PhaseOptions;
import soot.PrimType;
import soot.RefType;
import soot.Scene;
import soot.SceneTransformer;
import soot.SootClass;
import soot.SootField;
import soot.SootFieldRef;
import soot.SootMethod;
import soot.SootMethodRef;
import soot.Type;
import soot.Unit;
import soot.Value;
import soot.VoidType;
import soot.javaToJimple.LocalGenerator;
import soot.jimple.ArrayRef;
import soot.jimple.AssignStmt;
import soot.jimple.ClassConstant;
import soot.jimple.GotoStmt;
import soot.jimple.InstanceFieldRef;
import soot.jimple.InstanceInvokeExpr;
import soot.jimple.IntConstant;
import soot.jimple.InterfaceInvokeExpr;
import soot.jimple.InvokeExpr;
import soot.jimple.InvokeStmt;
import soot.jimple.Jimple;
import soot.jimple.JimpleBody;
import soot.jimple.NopStmt;
import soot.jimple.SpecialInvokeExpr;
import soot.jimple.StaticInvokeExpr;
import soot.jimple.Stmt;
import soot.jimple.StringConstant;
import soot.jimple.VirtualInvokeExpr;
import soot.jimple.toolkits.reflection.ReflectionTraceInfo.Kind;
import soot.jimple.toolkits.scalar.CopyPropagator;
import soot.jimple.toolkits.scalar.DeadAssignmentEliminator;
import soot.jimple.toolkits.scalar.NopEliminator;
import soot.options.CGOptions;
import soot.options.Options;
import soot.rtlib.DefaultHandler;
import soot.rtlib.IUnexpectedReflectiveCallHandler;
import soot.rtlib.OpaquePredicate;
import soot.rtlib.ReflectiveCalls;
import soot.rtlib.SootSig;
import soot.rtlib.UnexpectedReflectiveCall;
import soot.util.Chain;
import soot.util.HashChain;

public class ReflectiveCallsInliner extends SceneTransformer {
	//caching currently does not work because it adds fields to Class, Method and Constructor,
	//but such fields cannot currently be added using the Instrumentation API
	private final boolean useCaching = false;
	
	private static final String ALREADY_CHECKED_FIELDNAME = "SOOT$Reflection$alreadyChecked";
	private ReflectionTraceInfo RTI;
	private SootMethodRef UNINTERPRETED_METHOD;
	
	private boolean initialized = false;
	
	private int callSiteId;
	
	@Override
	protected void internalTransform(String phaseName, @SuppressWarnings("rawtypes") Map options) {
		if(!initialized) {
			CGOptions cgOptions = new CGOptions( PhaseOptions.v().getPhaseOptions("cg") );
			String logFilePath = cgOptions.reflection_log();
			RTI = new ReflectionTraceInfo(logFilePath);

			Scene.v().getSootClass(SootSig.class.getName()).setApplicationClass();
			Scene.v().getSootClass(UnexpectedReflectiveCall.class.getName()).setApplicationClass();
			Scene.v().getSootClass(IUnexpectedReflectiveCallHandler.class.getName()).setApplicationClass();
			Scene.v().getSootClass(DefaultHandler.class.getName()).setApplicationClass();
			Scene.v().getSootClass(OpaquePredicate.class.getName()).setApplicationClass();
			Scene.v().getSootClass(ReflectiveCalls.class.getName()).setApplicationClass();

			UNINTERPRETED_METHOD = Scene.v().makeMethodRef(Scene.v().getSootClass("soot.rtlib.OpaquePredicate"), "getFalse", Collections.<Type>emptyList(), BooleanType.v(), true);
			
			if(useCaching)
				addCaching();
			
			initializeReflectiveCallsTable();
			
			callSiteId = 0;
			
			initialized = true;
		}
		
		for(SootMethod m: RTI.methodsContainingReflectiveCalls()) {
			m.retrieveActiveBody();
			Body b = m.getActiveBody();
			{
				Set<String> classForNameClassNames = RTI.classForNameClassNames(m);
				if(!classForNameClassNames.isEmpty()) {
					inlineRelectiveCalls(m,classForNameClassNames, ReflectionTraceInfo.Kind.ClassForName);
					if(Options.v().validate()) b.validate();
				}
			}{
				Set<String> classNewInstanceClassNames = RTI.classNewInstanceClassNames(m);
				if(!classNewInstanceClassNames.isEmpty()) {
					inlineRelectiveCalls(m,classNewInstanceClassNames, ReflectionTraceInfo.Kind.ClassNewInstance);
					if(Options.v().validate()) b.validate();
				}
			}{
				Set<String> constructorNewInstanceSignatures = RTI.constructorNewInstanceSignatures(m);
				if(!constructorNewInstanceSignatures.isEmpty()) {
					inlineRelectiveCalls(m, constructorNewInstanceSignatures, ReflectionTraceInfo.Kind.ConstructorNewInstance);
					if(Options.v().validate()) b.validate();
				}
			}{
				Set<String> methodInvokeSignatures = RTI.methodInvokeSignatures(m);
				if(!methodInvokeSignatures.isEmpty()) {
					inlineRelectiveCalls(m, methodInvokeSignatures, ReflectionTraceInfo.Kind.MethodInvoke);
					if(Options.v().validate()) b.validate();
				}
			}
			//clean up after us
			DeadAssignmentEliminator.v().transform(b);
			CopyPropagator.v().transform(b);
			NopEliminator.v().transform(b);			
		}
	}

	private void initializeReflectiveCallsTable() {
		int callSiteId = 0;
		
		SootClass reflCallsClass = Scene.v().getSootClass("soot.rtlib.ReflectiveCalls");
		SootMethod clinit = reflCallsClass.getMethodByName(SootMethod.staticInitializerName);
		Body body = clinit.retrieveActiveBody();
		PatchingChain<Unit> units = body.getUnits();
		LocalGenerator localGen = new LocalGenerator(body);
		Chain<Unit> newUnits = new HashChain<Unit>();
		SootClass setClass = Scene.v().getSootClass("java.util.Set");
		SootMethodRef addMethodRef = setClass.getMethodByName("add").makeRef();
		for(SootMethod m: RTI.methodsContainingReflectiveCalls()) {
			{	
				if(!RTI.classForNameClassNames(m).isEmpty()) {
					SootFieldRef fieldRef = Scene.v().makeFieldRef(reflCallsClass, "classForName", RefType.v("java.util.Set"), true);
					Local setLocal = localGen.generateLocal(RefType.v("java.util.Set"));
					newUnits.add(Jimple.v().newAssignStmt(setLocal, Jimple.v().newStaticFieldRef(fieldRef)));
					for(String className: RTI.classForNameClassNames(m)) {
						InterfaceInvokeExpr invokeExpr = Jimple.v().newInterfaceInvokeExpr(setLocal, addMethodRef,StringConstant.v(callSiteId+className));
						newUnits.add(Jimple.v().newInvokeStmt(invokeExpr));
					}
					callSiteId++;
				}
			}	
			{	
				if(!RTI.classNewInstanceClassNames(m).isEmpty()) {
					SootFieldRef fieldRef = Scene.v().makeFieldRef(reflCallsClass, "classNewInstance", RefType.v("java.util.Set"), true);
					Local setLocal = localGen.generateLocal(RefType.v("java.util.Set"));
					newUnits.add(Jimple.v().newAssignStmt(setLocal, Jimple.v().newStaticFieldRef(fieldRef)));			
					for(String className: RTI.classNewInstanceClassNames(m)) {
						InterfaceInvokeExpr invokeExpr = Jimple.v().newInterfaceInvokeExpr(setLocal, addMethodRef,StringConstant.v(callSiteId+className));
						newUnits.add(Jimple.v().newInvokeStmt(invokeExpr));
					}
					callSiteId++;
				}
			}
			{	
				if(!RTI.constructorNewInstanceSignatures(m).isEmpty()) {
					SootFieldRef fieldRef = Scene.v().makeFieldRef(reflCallsClass, "constructorNewInstance", RefType.v("java.util.Set"), true);
					Local setLocal = localGen.generateLocal(RefType.v("java.util.Set"));
					newUnits.add(Jimple.v().newAssignStmt(setLocal, Jimple.v().newStaticFieldRef(fieldRef)));			
					for(String constrSig: RTI.constructorNewInstanceSignatures(m)) {
						InterfaceInvokeExpr invokeExpr = Jimple.v().newInterfaceInvokeExpr(setLocal, addMethodRef,StringConstant.v(callSiteId+constrSig));
						newUnits.add(Jimple.v().newInvokeStmt(invokeExpr));
					}
					callSiteId++;
				}
			}
			{	
				if(!RTI.methodInvokeSignatures(m).isEmpty()) {
					SootFieldRef fieldRef = Scene.v().makeFieldRef(reflCallsClass, "methodInvoke", RefType.v("java.util.Set"), true);
					Local setLocal = localGen.generateLocal(RefType.v("java.util.Set"));
					newUnits.add(Jimple.v().newAssignStmt(setLocal, Jimple.v().newStaticFieldRef(fieldRef)));			
					for(String methodSig: RTI.methodInvokeSignatures(m)) {
						InterfaceInvokeExpr invokeExpr = Jimple.v().newInterfaceInvokeExpr(setLocal, addMethodRef,StringConstant.v(callSiteId+methodSig));
						newUnits.add(Jimple.v().newInvokeStmt(invokeExpr));
					}
					callSiteId++;
				}
			}
		}	
		
		Unit secondLastStmt = units.getPredOf(units.getLast());
		units.insertAfter(newUnits, secondLastStmt);
		
		if(Options.v().validate()) body.validate();
	}

	private void addCaching() {
		SootClass method = Scene.v().getSootClass("java.lang.reflect.Method");
		method.addField(new SootField(ALREADY_CHECKED_FIELDNAME, BooleanType.v()));
		SootClass constructor = Scene.v().getSootClass("java.lang.reflect.Constructor");
		constructor.addField(new SootField(ALREADY_CHECKED_FIELDNAME, BooleanType.v()));
		SootClass clazz = Scene.v().getSootClass("java.lang.Class");
		clazz.addField(new SootField(ALREADY_CHECKED_FIELDNAME, BooleanType.v()));
		
		for(Kind k: Kind.values()) {
			addCaching(k);
		}
	}

	private void addCaching(Kind kind) {
		
		SootClass c;
		String methodName;
		switch(kind) {
		case ClassNewInstance:
			c = Scene.v().getSootClass("java.lang.Class");
			methodName = "knownClassNewInstance";
			break;
		case ConstructorNewInstance: 
			c = Scene.v().getSootClass("java.lang.reflect.Constructor");
			methodName = "knownConstructorNewInstance";
			break;
		case MethodInvoke: 
			c = Scene.v().getSootClass("java.lang.reflect.Method");
			methodName = "knownMethodInvoke";
			break;
		case ClassForName:
			//Cannot implement caching in this case because we can add no field to the String argument
			return;
		default:
			throw new IllegalStateException("unknown kind: "+kind);
		}
		
		SootClass reflCallsClass = Scene.v().getSootClass("soot.rtlib.ReflectiveCalls");
		
		SootMethod m = reflCallsClass.getMethodByName(methodName);
		JimpleBody body = (JimpleBody) m.retrieveActiveBody();
		LocalGenerator localGen = new LocalGenerator(body);
		Unit firstStmt = body.getFirstNonIdentityStmt();
		firstStmt = body.getUnits().getPredOf(firstStmt);
		
		Stmt jumpTarget = Jimple.v().newNopStmt();
		
		Chain<Unit> newUnits = new HashChain<Unit>();
		
		//alreadyCheckedLocal = m.alreadyChecked
		InstanceFieldRef fieldRef = Jimple.v().newInstanceFieldRef(body.getParameterLocal(m.getParameterCount()-1), Scene.v().makeFieldRef(c, ALREADY_CHECKED_FIELDNAME, BooleanType.v(), false));
		Local alreadyCheckedLocal = localGen.generateLocal(BooleanType.v());
		newUnits.add(Jimple.v().newAssignStmt(alreadyCheckedLocal, fieldRef));
		
		//if(!alreadyChecked) goto jumpTarget
		newUnits.add(Jimple.v().newIfStmt(Jimple.v().newEqExpr(alreadyCheckedLocal, IntConstant.v(0)), jumpTarget));
		
		//return
		newUnits.add(Jimple.v().newReturnVoidStmt());
		
		//jumpTarget: nop		
		newUnits.add(jumpTarget);
		
		//m.alreadyChecked = true
		InstanceFieldRef fieldRef2 = Jimple.v().newInstanceFieldRef(body.getParameterLocal(m.getParameterCount()-1), Scene.v().makeFieldRef(c, ALREADY_CHECKED_FIELDNAME, BooleanType.v(), false));
		newUnits.add(Jimple.v().newAssignStmt(fieldRef2, IntConstant.v(1)));
		
		body.getUnits().insertAfter(newUnits, firstStmt);
		
		if(Options.v().validate()) body.validate();
	}

	@SuppressWarnings("unchecked")
	private void inlineRelectiveCalls(SootMethod m, Set<String> targets, Kind callKind) {
		if(!m.hasActiveBody()) m.retrieveActiveBody();
		Body b = m.getActiveBody();
		PatchingChain<Unit> units = b.getUnits();
		Iterator<Unit> iter = units.snapshotIterator();
		LocalGenerator localGen = new LocalGenerator(b);
		
		//for all units
		while(iter.hasNext()) {
			Chain<Unit> newUnits = new HashChain<Unit>();
			Stmt s = (Stmt) iter.next();
			
			//if we have an invoke expression, test to see if it is a reflective invoke expression
			if(s.containsInvokeExpr()) {
				InvokeExpr ie = s.getInvokeExpr();
				boolean found = false;
				if(callKind==Kind.ClassForName && ie.getMethodRef().getSignature().equals("<java.lang.Class: java.lang.Class forName(java.lang.String)>")) {
					found = true;
					Value classNameValue = ie.getArg(0);					
					newUnits.add(Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(Scene.v().getMethod("<soot.rtlib.ReflectiveCalls: void knownClassForName(int,java.lang.String)>").makeRef(),IntConstant.v(callSiteId),classNameValue)));
				} else if(callKind==Kind.ClassNewInstance && ie.getMethodRef().getSignature().equals("<java.lang.Class: java.lang.Object newInstance()>")) {
					found = true;
					Local classLocal = (Local) ((InstanceInvokeExpr)ie).getBase();
					newUnits.add(Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(Scene.v().getMethod("<soot.rtlib.ReflectiveCalls: void knownClassNewInstance(int,java.lang.Class)>").makeRef(),IntConstant.v(callSiteId),classLocal)));
				} else if(callKind==Kind.ConstructorNewInstance && ie.getMethodRef().getSignature().equals("<java.lang.reflect.Constructor: java.lang.Object newInstance(java.lang.Object[])>")) {
					found = true;
					Local constrLocal = (Local) ((InstanceInvokeExpr)ie).getBase();
					newUnits.add(Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(Scene.v().getMethod("<soot.rtlib.ReflectiveCalls: void knownConstructorNewInstance(int,java.lang.reflect.Constructor)>").makeRef(),IntConstant.v(callSiteId),constrLocal)));
				} else if(callKind==Kind.MethodInvoke && ie.getMethodRef().getSignature().equals("<java.lang.reflect.Method: java.lang.Object invoke(java.lang.Object,java.lang.Object[])>")) {
					found = true;
					Local methodLocal = (Local) ((InstanceInvokeExpr)ie).getBase();
					Value recv = ie.getArg(0);
					newUnits.add(Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(Scene.v().getMethod("<soot.rtlib.ReflectiveCalls: void knownMethodInvoke(int,java.lang.Object,java.lang.reflect.Method)>").makeRef(),IntConstant.v(callSiteId),recv,methodLocal)));
				}
				
				if(!found) continue;
				
				NopStmt endLabel = Jimple.v().newNopStmt();

				//for all recorded targets
				for(String target : targets) {
					
					NopStmt jumpTarget = Jimple.v().newNopStmt();

					//boolean predLocal = Opaque.getFalse();
					Local predLocal = localGen.generateLocal(BooleanType.v());
					StaticInvokeExpr staticInvokeExpr = Jimple.v().newStaticInvokeExpr(UNINTERPRETED_METHOD);
					newUnits.add(Jimple.v().newAssignStmt(predLocal, staticInvokeExpr));
					//if predLocal == 0 goto <original reflective call>
					newUnits.add(Jimple.v().newIfStmt(Jimple.v().newEqExpr(IntConstant.v(0), predLocal), jumpTarget));

					Local freshLocal;
					Value replacement=null;
					Local[] paramLocals=null;
					switch(callKind) {
					case ClassForName: 
					{
						//replace by: <Class constant for <target>>
						freshLocal = localGen.generateLocal(RefType.v("java.lang.Class"));
						replacement = ClassConstant.v(target.replace('.','/'));
						break;
					}
					case ClassNewInstance:
					{
						//replace by: new <target>
						RefType targetType = RefType.v(target);
						freshLocal = localGen.generateLocal(targetType);
						replacement = Jimple.v().newNewExpr(targetType);
						break;
					}
					case ConstructorNewInstance:
					{
						/* replace r=constr.newInstance(args) by:
						 * Object p0 = args[0];
						 * ...
						 * Object pn = args[n];
						 * T0 a0 = (T0)p0;
						 * ...
						 * Tn an = (Tn)pn;
						 */
						SootMethod constructor = Scene.v().getMethod(target);
						paramLocals = new Local[constructor.getParameterCount()];
						if(constructor.getParameterCount()>0) {
							Local argsArrayLocal = (Local) s.getInvokeExpr().getArg(0);
							int i=0;
							for(Type paramType: ((Collection<Type>)constructor.getParameterTypes())) {
								paramLocals[i] = localGen.generateLocal(paramType);
								unboxParameter(argsArrayLocal, i, paramLocals, paramType, newUnits, localGen);
								i++;
							}
						} 
						RefType targetType = constructor.getDeclaringClass().getType();
						freshLocal = localGen.generateLocal(targetType);
						replacement = Jimple.v().newNewExpr(targetType);
						
						break;
					}
					case MethodInvoke: 
					{
						/* replace r=m.invoke(obj,args) by:
						 * T recv = (T)obj;
						 * Object p0 = args[0];
						 * ...
						 * Object pn = args[n];
						 * T0 a0 = (T0)p0;
						 * ...
						 * Tn an = (Tn)pn;
						 */
						SootMethod method = Scene.v().getMethod(target);
						Value recvObject = ie.getArg(0);
						paramLocals = new Local[method.getParameterCount()];
						if(method.getParameterCount()>0) {
							Local argsArrayLocal = (Local) s.getInvokeExpr().getArg(1);
							int i=0;
							for(Type paramType: ((Collection<Type>)method.getParameterTypes())) {
								paramLocals[i] = localGen.generateLocal(paramType);
								unboxParameter(argsArrayLocal, i, paramLocals, paramType, newUnits, localGen);							
								i++;
							}
						} 
						RefType targetType = method.getDeclaringClass().getType();
						freshLocal = localGen.generateLocal(targetType);
						replacement = Jimple.v().newCastExpr(recvObject, method.getDeclaringClass().getType());
						
						break;
					}
					default:
						throw new InternalError("Unknown kind of reflective call "+callKind);
					}
					
					AssignStmt replStmt = Jimple.v().newAssignStmt(freshLocal, replacement);
					newUnits.add(replStmt);
					
					switch(callKind) {
					case ClassNewInstance:
					{
						//add: r.<init>()
						SootClass targetClass = Scene.v().getSootClass(target);
						SpecialInvokeExpr constrCallExpr = Jimple.v().newSpecialInvokeExpr(freshLocal, Scene.v().makeMethodRef(targetClass, SootMethod.constructorName, Collections.<Type>emptyList(), VoidType.v(), false));
						InvokeStmt constrCallStmt2 = Jimple.v().newInvokeStmt(constrCallExpr);
						newUnits.add(constrCallStmt2);
						break;
					}
					case ConstructorNewInstance:
					{
						//add: r=<target>(a0,...,an);
						SootMethod constructor = Scene.v().getMethod(target);
						SpecialInvokeExpr constrCallExpr = Jimple.v().newSpecialInvokeExpr(freshLocal, constructor.makeRef(), Arrays.asList(paramLocals));
						InvokeStmt constrCallStmt2 = Jimple.v().newInvokeStmt(constrCallExpr);
						newUnits.add(constrCallStmt2);
						break;
					}
					case MethodInvoke:
						//add: r=recv.<target>(a0,...,an);
						SootMethod method = Scene.v().getMethod(target);
						InvokeExpr invokeExpr;
						if(method.isStatic())
							invokeExpr = Jimple.v().newStaticInvokeExpr(method.makeRef(), Arrays.asList(paramLocals));
						else
							invokeExpr = Jimple.v().newVirtualInvokeExpr(freshLocal, method.makeRef(), Arrays.asList(paramLocals));
						InvokeStmt invokeStmt = Jimple.v().newInvokeStmt(invokeExpr);
						newUnits.add(invokeStmt);
						break;
					} 
					
					if(s instanceof AssignStmt) {
						AssignStmt assignStmt = (AssignStmt) s;
						Value leftOp = assignStmt.getLeftOp();
						AssignStmt newAssignStmt = Jimple.v().newAssignStmt(leftOp, freshLocal);
						newUnits.add(newAssignStmt);
					}
						
					GotoStmt gotoStmt = Jimple.v().newGotoStmt(endLabel);
					newUnits.add(gotoStmt);
					
					newUnits.add(jumpTarget);
				}
				
				Unit end = newUnits.getLast();
				units.insertAfter(newUnits, s);
				units.remove(s);
				units.insertAfter(s, end);
				units.insertAfter(endLabel, s);
				
			}
		}
		callSiteId++;
	}

	
	/** Auto-unboxes an argument array.
	 * @param argsArrayLocal a local holding the argument Object[] array
	 * @param paramIndex the index of the parameter to unbox
	 * @param paramType the (target) type of the parameter
	 * @param newUnits the Unit chain to which the unboxing code will be appended
	 * @param localGen a {@link LocalGenerator} for the body holding the units
	 */
	private void unboxParameter(Local argsArrayLocal, int paramIndex, Local[] paramLocals, Type paramType, Chain<Unit> newUnits, LocalGenerator localGen) {
		ArrayRef arrayRef = Jimple.v().newArrayRef(argsArrayLocal, IntConstant.v(paramIndex));
		AssignStmt assignStmt;
		if(paramType instanceof PrimType) {
		    PrimType primType = (PrimType) paramType;
			// Unbox the value if needed
		    RefType boxedType = primType.boxedType();
			SootMethodRef ref = Scene.v().makeMethodRef(
		      boxedType.getSootClass(),
		      paramType + "Value",
		      Collections.<Type>emptyList(),
		      paramType,
		      false
		    );
		    Local boxedLocal = localGen.generateLocal(RefType.v("java.lang.Object"));
		    AssignStmt arrayLoad = Jimple.v().newAssignStmt(boxedLocal, arrayRef);
		    newUnits.add(arrayLoad);
		    Local castedLocal = localGen.generateLocal(boxedType);
		    AssignStmt cast = Jimple.v().newAssignStmt(castedLocal, Jimple.v().newCastExpr(boxedLocal, boxedType));
		    newUnits.add(cast);
		    VirtualInvokeExpr unboxInvokeExpr = Jimple.v().newVirtualInvokeExpr(castedLocal,ref);
			assignStmt = Jimple.v().newAssignStmt(paramLocals[paramIndex], unboxInvokeExpr);
		} else {
		    Local boxedLocal = localGen.generateLocal(RefType.v("java.lang.Object"));
		    AssignStmt arrayLoad = Jimple.v().newAssignStmt(boxedLocal, arrayRef);
		    newUnits.add(arrayLoad);
		    Local castedLocal = localGen.generateLocal(paramType);
		    AssignStmt cast = Jimple.v().newAssignStmt(castedLocal, Jimple.v().newCastExpr(boxedLocal, paramType));
		    newUnits.add(cast);
			assignStmt = Jimple.v().newAssignStmt(paramLocals[paramIndex], castedLocal);
		}
		newUnits.add(assignStmt);
	}

}