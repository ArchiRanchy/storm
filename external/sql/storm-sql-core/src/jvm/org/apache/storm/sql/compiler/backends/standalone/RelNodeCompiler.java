/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.storm.sql.compiler.backends.standalone;

import com.google.common.base.Joiner;
import com.google.common.primitives.Primitives;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.stream.Delta;
import org.apache.calcite.schema.AggregateFunction;
import org.apache.calcite.schema.impl.AggregateFunctionImpl;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.validate.SqlUserDefinedAggFunction;
import org.apache.storm.sql.compiler.ExprCompiler;
import org.apache.storm.sql.compiler.PostOrderRelNodeVisitor;

import java.io.PrintWriter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compile RelNodes into individual functions.
 */
class RelNodeCompiler extends PostOrderRelNodeVisitor<Void> {
  public static Joiner NEW_LINE_JOINER = Joiner.on('\n');

  private final PrintWriter pw;
  private final JavaTypeFactory typeFactory;
  private static final String STAGE_PROLOGUE = NEW_LINE_JOINER.join(
    "  private static final ChannelHandler %1$s = ",
    "    new AbstractChannelHandler() {",
    "    @Override",
    "    public void dataReceived(ChannelContext ctx, Values _data) {",
    "      if (_data != null) {",
    ""
  );

  private static final String AGGREGATE_STAGE_PROLOGUE = NEW_LINE_JOINER.join(
          "  private static final ChannelHandler %1$s = ",
          "    new AbstractChannelHandler() {",
          "    private final Values EMPTY_VALUES = new Values();",
          "    private List<Object> prevGroupValues = null;",
          "    private final Map<String, Object> accumulators = new HashMap<>();",
          "    private final int[] groupIndices = new int[] {%2$s};",
          "    private List<Object> getGroupValues(Values _data) {",
          "      List<Object> res = new ArrayList<>();",
          "      for (int i: groupIndices) {",
          "        res.add(_data.get(i));",
          "      }",
          "      return res;",
          "    }",
          "",
          "    @Override",
          "    public void dataReceived(ChannelContext ctx, Values _data) {",
          ""
  );
  private static final String STAGE_PASSTHROUGH = NEW_LINE_JOINER.join(
      "  private static final ChannelHandler %1$s = AbstractChannelHandler.PASS_THROUGH;",
      "");

  private int nameCount;
  private Map<AggregateCall, String> aggregateCallVarNames = new HashMap<>();

  RelNodeCompiler(PrintWriter pw, JavaTypeFactory typeFactory) {
    this.pw = pw;
    this.typeFactory = typeFactory;
  }

  @Override
  public Void visitDelta(Delta delta) throws Exception {
    pw.print(String.format(STAGE_PASSTHROUGH, getStageName(delta)));
    return null;
  }

  @Override
  public Void visitFilter(Filter filter) throws Exception {
    beginStage(filter);
    ExprCompiler compiler = new ExprCompiler(pw, typeFactory);
    String r = filter.getCondition().accept(compiler);
    if (filter.getCondition().getType().isNullable()) {
      pw.print(String.format("    if (%s != null && %s) { ctx.emit(_data); }\n", r, r));
    } else {
      pw.print(String.format("    if (%s) { ctx.emit(_data); }\n", r, r));
    }
    endStage();
    return null;
  }

  @Override
  public Void visitProject(Project project) throws Exception {
    beginStage(project);
    ExprCompiler compiler = new ExprCompiler(pw, typeFactory);
    int size = project.getChildExps().size();
    String[] res = new String[size];
    for (int i = 0; i < size; ++i) {
      res[i] = project.getChildExps().get(i).accept(compiler);
    }

    pw.print(String.format("    ctx.emit(new Values(%s));\n",
                           Joiner.on(',').join(res)));
    endStage();
    return null;
  }

  @Override
  public Void defaultValue(RelNode n) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Void visitTableScan(TableScan scan) throws Exception {
    pw.print(String.format(STAGE_PASSTHROUGH, getStageName(scan)));
    return null;
  }

  @Override
  public Void visitAggregate(Aggregate aggregate) throws Exception {
    beginAggregateStage(aggregate);
    pw.println("        List<Object> curGroupValues = _data == null ? null : getGroupValues(_data);");
    pw.println("        if (curGroupValues == null || (prevGroupValues != null && ! prevGroupValues.equals(curGroupValues))) {");
    List<String> res = new ArrayList<>();
    for (AggregateCall call : aggregate.getAggCallList()) {
      res.add(aggregateResult(call));
    }
    pw.print(String.format("          ctx.emit(new Values(%s, %s));\n",
                           groupValueEmitStr("prevGroupValues", aggregate.getGroupSet().cardinality()),
                           Joiner.on(", ").join(res)));
    pw.println("          accumulators.clear();");
    pw.println("        }");
    pw.println("        if (curGroupValues != null) {");
    for (AggregateCall call : aggregate.getAggCallList()) {
      aggregate(call);
    }
    pw.println("        }");
    pw.println("        if (prevGroupValues != curGroupValues) {");
    pw.println("          prevGroupValues = curGroupValues;");
    pw.println("        }");
    endAggregateStage();
    return null;
  }

  private String groupValueEmitStr(String var, int n) {
    int count = 0;
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      if (++count > 1) {
        sb.append(", ");
      }
      sb.append(var).append(".").append("get(").append(i).append(")");
    }
    return sb.toString();
  }

  private String aggregateResult(AggregateCall call) {
    SqlAggFunction aggFunction = call.getAggregation();
    String aggregationName = call.getAggregation().getName();
    Type ty = typeFactory.getJavaClass(call.getType());
    String result;
    if (aggFunction instanceof SqlUserDefinedAggFunction) {
      AggregateFunction aggregateFunction = ((SqlUserDefinedAggFunction) aggFunction).function;
      result = doAggregateResult((AggregateFunctionImpl) aggregateFunction, reserveAggVarName(call), ty);
    } else {
      List<BuiltinAggregateFunctions.TypeClass> typeClasses = BuiltinAggregateFunctions.TABLE.get(aggregationName);
      if (typeClasses == null) {
        throw new UnsupportedOperationException(aggregationName + " Not implemented");
      }
      result = doAggregateResult(AggregateFunctionImpl.create(findMatchingClass(aggregationName, typeClasses, ty)),
                                 reserveAggVarName(call), ty);
    }
    return result;
  }

  private String doAggregateResult(AggregateFunctionImpl aggFn, String varName, Type ty) {
    String resultName = varName + "_result";
    List<String> args = new ArrayList<>();
    if (!aggFn.isStatic) {
      String aggObjName = String.format("%s_obj", varName);
      String aggObjClassName = (aggFn.initMethod.getDeclaringClass().getCanonicalName());
      boolean genericType = aggFn.initMethod.getDeclaringClass().getTypeParameters().length > 0;
      if (genericType) {
        pw.println("          @SuppressWarnings(\"unchecked\")");
        pw.println(String.format("          final %1$s<%3$s> %2$s = (%1$s<%3$s>) accumulators.get(\"%2$s\");", aggObjClassName,
                                 aggObjName, Primitives.wrap((Class<?>) ty).getCanonicalName()));
      } else {
        pw.println(String.format("          final %1$s %2$s = (%1$s) accumulators.get(\"%2$s\");", aggObjClassName, aggObjName));
      }
      args.add(aggObjName);
    }
    args.add(String.format("(%s)accumulators.get(\"%s\")", ((Class<?>) ty).getCanonicalName(), varName));
    pw.println(String.format("          final %s %s = %s;", ((Class<?>) ty).getCanonicalName(),
                             resultName, ExprCompiler.printMethodCall(aggFn.resultMethod, args)));

    return resultName;
  }

  private void aggregate(AggregateCall call) {
    SqlAggFunction aggFunction = call.getAggregation();
    String aggregationName = call.getAggregation().getName();
    Type ty = typeFactory.getJavaClass(call.getType());
    if (call.getArgList().size() != 1) {
      if (aggregationName.equals("COUNT")) {
        if (call.getArgList().size() != 0) {
          throw new UnsupportedOperationException("Count with nullable fields");
        }
      } else {
        throw new IllegalArgumentException("Aggregate call should have one argument");
      }
    }
    if (aggFunction instanceof SqlUserDefinedAggFunction) {
      AggregateFunction aggregateFunction = ((SqlUserDefinedAggFunction) aggFunction).function;
      doAggregate((AggregateFunctionImpl) aggregateFunction, reserveAggVarName(call), ty, call.getArgList());
    } else {
      List<BuiltinAggregateFunctions.TypeClass> typeClasses = BuiltinAggregateFunctions.TABLE.get(aggregationName);
      if (typeClasses == null) {
        throw new UnsupportedOperationException(aggregationName + " Not implemented");
      }
      doAggregate(AggregateFunctionImpl.create(findMatchingClass(aggregationName, typeClasses, ty)),
                  reserveAggVarName(call), ty, call.getArgList());
    }
  }

  private Class<?> findMatchingClass(String aggregationName, List<BuiltinAggregateFunctions.TypeClass> typeClasses, Type ty) {
    for (BuiltinAggregateFunctions.TypeClass typeClass : typeClasses) {
      if (typeClass.ty.equals(BuiltinAggregateFunctions.TypeClass.GenericType.class) || typeClass.ty.equals(ty)) {
        return typeClass.clazz;
      }
    }
    throw new UnsupportedOperationException(aggregationName + " Not implemeted for type '" + ty + "'");
  }

  private void doAggregate(AggregateFunctionImpl aggFn, String varName, Type ty, List<Integer> argList) {
    List<String> args = new ArrayList<>();
    if (!aggFn.isStatic) {
      String aggObjName = String.format("%s_obj", varName);
      String aggObjClassName = aggFn.initMethod.getDeclaringClass().getCanonicalName();
      pw.println(String.format("          if (!accumulators.containsKey(\"%s\")) { ", aggObjName));
      pw.println(String.format("            accumulators.put(\"%s\", new %s());", aggObjName, aggObjClassName));
      pw.println("          }");
      boolean genericType = aggFn.initMethod.getDeclaringClass().getTypeParameters().length > 0;
      if (genericType) {
        pw.println("          @SuppressWarnings(\"unchecked\")");
        pw.println(String.format("          final %1$s<%3$s> %2$s = (%1$s<%3$s>) accumulators.get(\"%2$s\");", aggObjClassName,
                                 aggObjName, Primitives.wrap((Class<?>) ty).getCanonicalName()));
      } else {
        pw.println(String.format("          final %1$s %2$s = (%1$s) accumulators.get(\"%2$s\");", aggObjClassName, aggObjName));
      }
      args.add(aggObjName);
    }
    args.add(String.format("%1$s == null ? %2$s : (%3$s) %1$s",
                           "accumulators.get(\"" + varName + "\")",
                           ExprCompiler.printMethodCall(aggFn.initMethod, args),
                           Primitives.wrap((Class<?>) ty).getCanonicalName()));
    if (argList.isEmpty()) {
      args.add("EMPTY_VALUES");
    } else {
      args.add(String.format("(%s) %s", ((Class<?>) ty).getCanonicalName(), "_data.get(" + argList.get(0) + ")"));
    }
    pw.print(String.format("          accumulators.put(\"%s\", %s);\n",
                           varName,
                           ExprCompiler.printMethodCall(aggFn.addMethod, args)));
  }

  private String reserveAggVarName(AggregateCall call) {
    String varName;
    if ((varName = aggregateCallVarNames.get(call)) == null) {
      varName = call.getAggregation().getName() + ++nameCount;
      aggregateCallVarNames.put(call, varName);
    }
    return varName;
  }

  private void beginStage(RelNode n) {
    pw.print(String.format(STAGE_PROLOGUE, getStageName(n)));
  }

  private void beginAggregateStage(Aggregate n) {
    pw.print(String.format(AGGREGATE_STAGE_PROLOGUE, getStageName(n), getGroupByIndices(n)));
  }

  private void endStage() {
    pw.println("      } else {");
    pw.println("        ctx.emit(_data);");
    pw.println("      }");
    pw.print("  }\n  };\n");
  }

  private void endAggregateStage() {
    pw.print("  }\n  };\n");
  }

  static String getStageName(RelNode n) {
    return n.getClass().getSimpleName().toUpperCase() + "_" + n.getId();
  }

  private String getGroupByIndices(Aggregate n) {
    StringBuilder res = new StringBuilder();
    int count = 0;
    for (int i : n.getGroupSet()) {
      if (++count > 1) {
        res.append(", ");
      }
      res.append(i);
    }
    return res.toString();
  }
}
