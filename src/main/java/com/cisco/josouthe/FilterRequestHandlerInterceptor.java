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
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class FilterRequestHandlerInterceptor extends MyBaseInterceptor {

    private IReflector getRequest;
    private IReflector getRequestId, getClientKey, getMethod, getURL, getQuery, getStatus;
    private IReflector getHeader, getHeaderNames;

    public FilterRequestHandlerInterceptor() {
        super();

        getRequest = getNewReflectionBuilder().accessFieldValue("request", true).build();

        getRequestId = getNewReflectionBuilder().accessFieldValue("requestId", true).invokeInstanceMethod("toString", true).build(); //String
        getClientKey = getNewReflectionBuilder().accessFieldValue("clientKey", true).invokeInstanceMethod("asString", true).build(); //String
        getMethod = getNewReflectionBuilder().invokeInstanceMethod("getMethod", true).build(); //String
        getURL = getNewReflectionBuilder().invokeInstanceMethod("getRequestURL", true).invokeInstanceMethod("toString", true).build(); //String
        getQuery = getNewReflectionBuilder().invokeInstanceMethod("getQueryString", true).build(); //String
        getStatus = getNewReflectionBuilder().accessFieldValue("response", true).invokeInstanceMethod("getStatus", true).build(); //Integer

        getHeader = getNewReflectionBuilder().invokeInstanceMethod("getHeader", true, new String[]{ String.class.getCanonicalName()}).build(); //String
        getHeaderNames = getNewReflectionBuilder().invokeInstanceMethod( "getHeaderNames", true ).build(); //Enumeration<String>
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

        Enumeration<String> headerNamesEnumeration = null;
        try {
            headerNamesEnumeration = (Enumeration<String>) getHeaderNames.execute(request.getClass().getClassLoader(), request);
        } catch (ReflectorException e) {
            getLogger().info(String.format("Warning: getHeaderNames reflection exception: %s", e.getMessage()));
        }

        Map<String,String> appdHeadersMap = new HashMap<>();
        if( headerNamesEnumeration != null ) {
            while( headerNamesEnumeration.hasMoreElements() ) {
                String headerName = headerNamesEnumeration.nextElement();
                String headerValue = getHeader(request,headerName);
                if( headerValue != null ) appdHeadersMap.put(headerName,headerValue);
            }
            if( !appdHeadersMap.isEmpty() ) builder.withHeaders(appdHeadersMap);
        }

        getLogger().debug(String.format("url: '%s' method: '%s' headers: '%s'(%d) parameters: '%s'", url, method, appdHeadersMap, appdHeadersMap.size(), getReflectiveString(request, getQuery, "UNKNOWN-QUERY")));

        return builder.build();
    }

    private String getCorrelationHeader(Object request) {
        if( request == null ) return null;
        String value = getHeader(request, AppdynamicsAgent.TRANSACTION_CORRELATION_HEADER);
        if( "".equals(value) && value != null ) {
            getLogger().debug("Correlation header value is not null but is equal to an empty string, this may be the issue we are looking for, returning null instead of an empty string");
            return null;
        }
        return value;
    }

    private String getHeader(Object request, String headerName) {
        if( request == null ) return null;
        try {
            String headerValue = (String) getHeader.execute(request.getClass().getClassLoader(), request, new Object[]{ headerName });
            getLogger().debug(String.format("Header Value: '%s':'%s'", headerName, headerValue));
            return headerValue;
        } catch (ReflectorException e) {
            getLogger().info(String.format("Exception trying to read header: %s, Exception: %s",headerName, e ));
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
