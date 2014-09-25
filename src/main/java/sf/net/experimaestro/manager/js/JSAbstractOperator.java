package sf.net.experimaestro.manager.js;

import com.google.common.collect.ImmutableMap;
import org.apache.commons.lang.NotImplementedException;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Scriptable;
import sf.net.experimaestro.exceptions.XPMRhinoException;
import sf.net.experimaestro.manager.Manager;
import sf.net.experimaestro.manager.QName;
import sf.net.experimaestro.manager.plans.*;
import sf.net.experimaestro.scheduler.Resource;
import sf.net.experimaestro.utils.JSNamespaceContext;

import javax.xml.xpath.XPathExpressionException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;

/**
 * @author B. Piwowarski <benjamin@bpiwowar.net>
 * @date 8/3/13
 */
public abstract class JSAbstractOperator extends JSBaseObject {
    /**
     * Get the associated operator
     */
    abstract Operator getOperator();

//    @JSFunction(scope = true)
//    @JSHelp("Runs an XQuery against the input: each returned item is a new input")
//    public JSAbstractOperator xpath(Context context, Scriptable scope, String xpath) throws XPathExpressionException {
//        XPathFunction function = new XPathFunction(xpath, JSUtils.getNamespaceContext(scope));
//        Operator operator = new FunctionOperator(function);
//        operator.addParent(this.getOperator());
//        return new JSOperator(operator);
//    }

    @JSFunction(scope = true)
    @JSHelp("Runs an XQuery against the input: each returned item is a new input")
    public JSAbstractOperator select(Context context, Scriptable scope, String query) throws XPathExpressionException {
        JsonPathFunction function = new JsonPathFunction(query, scope);
        Operator operator = new FunctionOperator(function);
        operator.addParent(this.getOperator());
        return new JSOperator(operator);
    }


    @JSFunction
    public JSOperator group_by(JSAbstractOperator... operators) {
        return group_by(Manager.XP_ARRAY, operators);
    }

    @JSFunction(scope = true)
    public JSOperator group_by(Context cx, Scriptable scope, String name, JSAbstractOperator... operators) {
        return group_by(QName.parse(name, new JSNamespaceContext(scope)), operators);
    }

    public JSOperator group_by(QName qname, JSAbstractOperator... operators) {
        GroupBy groupBy = new GroupBy(qname);

        // Get ancestors
        HashSet<Operator> ancestors = new HashSet<>();
        getOperator().getAncestors(ancestors);

        // Order using the operators we should group by
        Order<Operator> order = new Order();
        int i = 0;
        for (JSAbstractOperator jsOperator : operators) {
            i++;
            Operator operator = jsOperator.getOperator();
            if (!ancestors.contains(operator))
                throw new XPMRhinoException("group_by() %dth argument is not an ancestor", i);
            groupBy.add(operator);
            order.add(operator, false);
        }
        OrderBy orderBy = new OrderBy(order, null);
        orderBy.addParent(getOperator());

        groupBy.addParent(orderBy);

        return new JSOperator(groupBy);
    }

    @JSFunction
    public JSOperator copy() {
        return new JSOperator(getOperator().copy(true));
    }

    @JSFunction(scope = true)
    public JSAbstractOperator merge(Context cx, Scriptable scope, String outputType, Object... objects) {
        if (objects.length == 0)
            return this;

        Object allObjects[] = new Object[objects.length+1];
        System.arraycopy(objects, 0, allObjects, 1, objects.length);
        allObjects[0] = this;
        return JSTasks.merge(cx, scope, outputType, allObjects);
    }

    @JSFunction(scope = true)
    public JSAbstractOperator merge(Context cx, Scriptable scope, String outputType, String key, Object... objects) {
        Object allObjects[] = new Object[objects.length+1];
        System.arraycopy(objects, 0, allObjects, 1, objects.length);

        NativeObject jsobject = new NativeObject();
        jsobject.put(key, jsobject, this);
        allObjects[0] = jsobject;
        return JSTasks.merge(cx, scope, outputType, allObjects);
    }


    @JSFunction("to_dot")
    public String toDot(boolean simplify) throws XPathExpressionException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos);
        Operator operator = getOperator().prepare();
        if (simplify)
            operator = Operator.simplify(operator);
        operator.printDOT(ps);
        return baos.toString();
    }


    @JSFunction("to_dot")
    public String toDOT(boolean simplify, boolean initialize) throws XPathExpressionException {
        Operator operator = getOperator(simplify, initialize);


        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos);

        operator.printDOT(ps);
        ps.flush();
        return baos.toString();
    }

    @JSFunction(value = "set_default_locks", optional = 1)
    public void setDefaultLock(Object locks) {
        if (locks != null) {
            throw new NotImplementedException("Set default lock on operators");
        }
        Map<Resource, String> _empty = ImmutableMap.of();
        getOperator().setDefaultLocks(_empty);
    }


    private Operator getOperator(boolean simplify, boolean initialize) throws XPathExpressionException {
        Operator operator = getOperator();

        operator = operator.prepare();
        if (simplify)
            operator = Operator.simplify(operator);
        if (initialize)
            operator.init();

        return operator;
    }

    @JSFunction
    public Object run() throws XPathExpressionException {
        return doRun(false, false);
    }

    @JSFunction
    public Object simulate() throws XPathExpressionException {
        return doRun(true, false);
    }

    @JSFunction
    public Object simulate(boolean details) throws XPathExpressionException {
        return doRun(true, details);
    }

    private Object doRun(boolean simulate, boolean details) throws XPathExpressionException {
        PlanContext planContext = new PlanContext(simulate);
        planContext.counts(details);

        ArrayList<JSJson> result = new ArrayList<>();
        Operator operator = getOperator(true, true);

        PlanScope scope = new PlanScope();
        final Iterator<Value> nodes = operator.iterator(planContext);
        while (nodes.hasNext()) {
            result.add(new JSJson(nodes.next().getNodes()[0]));
        }

        if (!details)
            return result.toArray(new JSJson[result.size()]);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos);
        operator.printDOT(ps, planContext.counts());
        ps.flush();

        return new NativeArray(new Object[]{result, baos.toString()});
    }

}