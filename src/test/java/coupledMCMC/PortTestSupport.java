package coupledMCMC;

import beast.base.inference.Operator;
import beast.base.inference.OperatorSchedule;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

/** Small fixtures for source-port regressions; no model or file I/O. */
final class PortTestSupport {
    private PortTestSupport() { }

    static Object invoke(CoupledMCMC controller, String name, Class<?>[] types, Object... arguments)
            throws Exception {
        Method method = CoupledMCMC.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(controller, arguments);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
            if (e.getCause() instanceof Error) throw (Error) e.getCause();
            throw e;
        }
    }

    static class ScalarOperator extends Operator {
        double scalar;
        boolean failRead;
        int writes;

        ScalarOperator(String id, int base) {
            setID(id);
            setAcceptedRejected(base + 1, base + 2, base + 3, base + 4);
            scalar = base + 0.5;
            m_nNrRejectedInvalid = base + 5;
            m_nNrRejectedOperator = base + 6;
        }

        @Override public void initAndValidate() { }
        @Override public double proposal() { return 0.0; }
        @Override public double getCoercableParameterValue() {
            if (failRead) throw new IllegalStateException("deliberate payload read failure");
            return scalar;
        }
        @Override public void setCoercableParameterValue(double value) {
            writes++;
            scalar = value;
        }
        int invalidRejections() { return m_nNrRejectedInvalid; }
        int operatorRejections() { return m_nNrRejectedOperator; }
    }

    static final class OtherOperator extends ScalarOperator {
        OtherOperator(String id, int base) { super(id, base); }
    }

    static class ResidentChain extends HeatedChain {
        final OperatorSchedule schedule = new OperatorSchedule();

        ResidentChain(int rank, Operator... operators) {
            setChainNr(rank);
            // Deliberately leave operatorsInput empty: the active inventory is authoritative.
            schedule.operators.addAll(Arrays.asList(operators));
        }

        @Override public OperatorSchedule getOperatorSchedule() { return schedule; }
    }
}
