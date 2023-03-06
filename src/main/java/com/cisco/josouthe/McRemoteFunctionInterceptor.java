package com.cisco.josouthe;

import com.appdynamics.agent.api.AppdynamicsAgent;
import com.appdynamics.agent.api.ExitCall;
import com.appdynamics.agent.api.ExitTypes;
import com.appdynamics.instrumentation.sdk.Rule;
import com.appdynamics.instrumentation.sdk.SDKClassMatchType;
import com.appdynamics.instrumentation.sdk.SDKStringMatchType;
import com.appdynamics.instrumentation.sdk.toolbox.reflection.IReflector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class McRemoteFunctionInterceptor extends MyBaseInterceptor {

    IReflector getFunctionName, getVersionString, getHost, getPort;

    public McRemoteFunctionInterceptor() {
        super();
        getFunctionName = getNewReflectionBuilder().invokeInstanceMethod("getFunctionName", true).build();
        getVersionString = getNewReflectionBuilder()
                .invokeInstanceMethod("getRemoteInterface", true)
                .invokeInstanceMethod("getServerConnection", true)
                .invokeInstanceMethod("getVersionString", true)
                .build();
        getHost = getNewReflectionBuilder()
                .invokeInstanceMethod("getRemoteInterface", true)
                .invokeInstanceMethod("getServerConnection", true)
                .invokeInstanceMethod("getHost", true)
                .build();
        getPort = getNewReflectionBuilder()
                .invokeInstanceMethod("getRemoteInterface", true)
                .invokeInstanceMethod("getServerConnection", true)
                .invokeInstanceMethod("getPort", true)
                .build();
    }

    @Override
    public Object onMethodBegin(Object objectIntercepted, String className, String methodName, Object[] params) {
        Map<String,String> map = new HashMap<>();
        map.put("Host", getReflectiveString(objectIntercepted, getHost, "UNKNOWN-HOST"));
        map.put("Port", getReflectiveString(objectIntercepted, getPort, "UNKNOWN-PORT"));
        map.put("Version", getReflectiveString(objectIntercepted, getVersionString, "UNKNOWN-VERSION"));
        String functionName = getReflectiveString(objectIntercepted, getFunctionName, "UNKNOWN-FUNCTION");
        map.put("Function", functionName);
        String functionBaseName = functionName.split(":")[0];
        ExitCall exitCall = AppdynamicsAgent.getTransaction().startExitCall(map, "Maconomy:"+ functionBaseName, ExitTypes.CUSTOM, false);
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
        //com.maconomy.server.io.McIOValueReader.peekByte() is waiting on an external service, it is too chatty
        //com.maconomy.server.rpc.McRemoteFunction:call:137
        // getFunctionName()
        // getRemoteInterface().getServerConnection().getVersionString()
        // getRemoteInterface().getServerConnection().getHost()
        // getRemoteInterface().getServerConnection().getPort()
        List<Rule> rules = new ArrayList<Rule>();
        rules.add(new Rule.Builder(
                "com.maconomy.server.rpc.McRemoteFunction")
                .classMatchType(SDKClassMatchType.MATCHES_CLASS)
                .methodMatchString("call")
                .methodStringMatchType( SDKStringMatchType.EQUALS)
                .build()
        );

        return rules;
    }
}
