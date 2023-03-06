package com.cisco.josouthe;

import com.appdynamics.agent.api.AppdynamicsAgent;
import com.appdynamics.agent.api.ExitCall;
import com.appdynamics.agent.api.ExitTypes;
import com.appdynamics.instrumentation.sdk.Rule;
import com.appdynamics.instrumentation.sdk.SDKClassMatchType;
import com.appdynamics.instrumentation.sdk.SDKStringMatchType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class McIOValueReaderInterceptor extends MyBaseInterceptor {
    @Override
    public Object onMethodBegin(Object objectIntercepted, String className, String methodName, Object[] params) {
        Map<String,String> map = new HashMap<>();
        ExitCall exitCall = AppdynamicsAgent.getTransaction().startExitCall(map, "MaconomyBackend", ExitTypes.CUSTOM, false);
        return exitCall;
    }

    @Override
    public void onMethodEnd(Object state, Object objectIntercepted, String className, String methodName, Object[] params, Throwable exception, Object returnVal) {
        if( state == null ) return;
        ExitCall exitCall = (ExitCall) state;
        if( exception != null ) {
            AppdynamicsAgent.getTransaction().markAsError("Exception in backend call to Maconomy Service: "+ String.valueOf(exception));
        }
        exitCall.end();
    }

    @Override
    public List<Rule> initializeRules() {
        //com.maconomy.server.io.McIOValueReader.peekByte() is waiting on an external service
        List<Rule> rules = new ArrayList<Rule>();
        rules.add(new Rule.Builder(
                "com.maconomy.server.io.McIOValueReader")
                .classMatchType(SDKClassMatchType.MATCHES_CLASS)
                .methodMatchString("peekByte")
                .methodStringMatchType( SDKStringMatchType.EQUALS)
                .build()
        );

        return rules;
    }
}
