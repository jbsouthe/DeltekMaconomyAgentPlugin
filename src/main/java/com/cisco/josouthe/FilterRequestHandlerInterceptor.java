package com.cisco.josouthe;

import com.appdynamics.agent.api.AppdynamicsAgent;
import com.appdynamics.agent.api.EntryTypes;
import com.appdynamics.agent.api.ServletContext;
import com.appdynamics.agent.api.Transaction;
import com.appdynamics.agent.api.impl.NoOpTransaction;
import com.appdynamics.instrumentation.sdk.Rule;
import com.appdynamics.instrumentation.sdk.SDKClassMatchType;
import com.appdynamics.instrumentation.sdk.SDKStringMatchType;
import com.appdynamics.instrumentation.sdk.toolbox.reflection.IReflector;
import com.appdynamics.instrumentation.sdk.toolbox.reflection.ReflectorException;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class FilterRequestHandlerInterceptor extends MyBaseInterceptor {

    private IReflector getRequest;
    private IReflector getRequestId, getClientKey, getMethod, getURL, getQuery, getStatus;
    private IReflector getHeader;

    public FilterRequestHandlerInterceptor() {
        super();

        getRequest = getNewReflectionBuilder().accessFieldValue("request", true).build();

        getRequestId = getNewReflectionBuilder().accessFieldValue("requestId", true).invokeInstanceMethod("toString", true).build(); //String
        getClientKey = getNewReflectionBuilder().accessFieldValue("clientKey", true).invokeInstanceMethod("asString", true).build(); //String
        getMethod = getNewReflectionBuilder().invokeInstanceMethod("getMethod", true).build(); //String
        getURL = getNewReflectionBuilder().invokeInstanceMethod("getRequestURL", true).invokeInstanceMethod("toString", true).build(); //String
        getQuery = getNewReflectionBuilder().invokeInstanceMethod("getQueryString", true).build(); //String
        getStatus = getNewReflectionBuilder().accessFieldValue("response", true).invokeInstanceMethod("getStatus", true).build(); //Integer

        getHeader = getNewReflectionBuilder().accessFieldValue("request", true).invokeInstanceMethod("getHeader", true, new String[]{ String.class.getCanonicalName()}).build(); //String
    }

    @Override
    public Object onMethodBegin(Object objectIntercepted, String className, String methodName, Object[] params) {
        this.getLogger().debug("McWorkspaceApiInterceptor.onMethodBegin() start: "+ className +"."+ methodName +"()");

        Transaction transaction = AppdynamicsAgent.getTransaction();
        if( transaction instanceof NoOpTransaction ) {
            Object request = getReflectiveObject( objectIntercepted, getRequest);
            getLogger().debug("Got a request Object: "+ String.valueOf(request));
            transaction = AppdynamicsAgent.startServletTransaction( buildServletContext(request), getCorrelationHeader(request), EntryTypes.HTTP, false);
        } else {
            getLogger().debug(String.format("BT is already configured for this servlet, guid: %s",transaction.getUniqueIdentifier()));
        }
        String requestID = getReflectiveString(objectIntercepted, getRequestId, "UNKNOWN-ID");
        String clientName = getReflectiveString(objectIntercepted, getClientKey, "UNKNOWN-CLIENT");
        transaction.collectData("RequestID", requestID, snapshotDatascopeOnly );
        transaction.collectData("Client", clientName , dataScopes );
        getLogger().debug(String.format("Captured Custom Data, BT: '%s', RequestID: '%s', Client: '%s'", transaction.getUniqueIdentifier(), requestID, clientName));
        this.getLogger().debug("McWorkspaceApiInterceptor.onMethodBegin() finish: "+ className +"."+ methodName +"()");

        return transaction;
    }

    private ServletContext buildServletContext(Object request) {
        ServletContext.ServletContextBuilder builder = new ServletContext.ServletContextBuilder();
        String url = getReflectiveString(request, getURL, "/unknown-url");
        try {
            builder.withURL( url );
        } catch (MalformedURLException e) {
            getLogger().info(String.format("Warning: URL exception: %s", e.getMessage()));
        }

        String method = getReflectiveString(request,getMethod,"GET");
        builder.withRequestMethod( method );

        getLogger().debug(String.format("url: '%s' method: '%s' parameters: '%s'", url, method, getReflectiveString(request, getQuery, "UNKNOWN-QUERY")));

        return builder.build();
    }

    private String getCorrelationHeader(Object request) {
        if( request == null ) return null;
        try {
            String correlationHeaderValue = (String) getHeader.execute(request.getClass().getClassLoader(), request, new Object[]{ AppdynamicsAgent.TRANSACTION_CORRELATION_HEADER });
            getLogger().debug(String.format("Correlation Header Value: '%s'", correlationHeaderValue));
            return correlationHeaderValue;
        } catch (ReflectorException e) {
            getLogger().info(String.format("Exception trying to read transaction correlation header: %s", e ));
        }
        return null;
    }

    @Override
    public void onMethodEnd(Object state, Object object, String className, String methodName, Object[] params, Throwable exception, Object returnVal) {
        this.getLogger().debug("McWorkspaceApiInterceptor.onMethodEnd() start: "+ className +"."+ methodName +"()");
        Transaction transaction = (Transaction) state;
        if( transaction == null ) return;
        if( exception != null ) {
            transaction.markAsError(String.format("Error: %s", exception.getMessage()));
        }
        int status = getReflectiveInteger(object, getStatus, -1);
        if( status == -1 ) getLogger().info("HTTP Response status is not set?");
        if( status > 399 ) {
            transaction.markAsError(String.format("HTTP Response Status is %d", status));
        }
        transaction.end();
        this.getLogger().debug("McWorkspaceApiInterceptor.onMethodEnd() finish: "+ className +"."+ methodName +"()");
    }

    @Override
    public List<Rule> initializeRules() {
        List<Rule> rules = new ArrayList<Rule>();
        rules.add(new Rule.Builder(
                "com.maconomy.webservices.common.mcontext.McMaconomyContextFilter$FilterRequestHandler")
                .classMatchType(SDKClassMatchType.MATCHES_CLASS)
                .methodMatchString("handleRequest")
                .methodStringMatchType( SDKStringMatchType.EQUALS)
                .build()
        );

        return rules;
    }
}
