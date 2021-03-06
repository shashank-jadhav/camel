/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.salesforce;

import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.camel.CamelContext;
import org.apache.camel.ComponentVerifier;
import org.apache.camel.Endpoint;
import org.apache.camel.SSLContextParametersAware;
import org.apache.camel.VerifiableComponent;
import org.apache.camel.component.salesforce.api.SalesforceException;
import org.apache.camel.component.salesforce.api.dto.AbstractSObjectBase;
import org.apache.camel.component.salesforce.internal.OperationName;
import org.apache.camel.component.salesforce.internal.SalesforceSession;
import org.apache.camel.component.salesforce.internal.streaming.SubscriptionHelper;
import org.apache.camel.impl.DefaultComponent;
import org.apache.camel.spi.Metadata;
import org.apache.camel.util.IntrospectionSupport;
import org.apache.camel.util.ServiceHelper;
import org.apache.camel.util.StringHelper;
import org.apache.camel.util.jsse.KeyStoreParameters;
import org.apache.camel.util.jsse.SSLContextParameters;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.ProxyConfiguration;
import org.eclipse.jetty.client.Socks4Proxy;
import org.eclipse.jetty.client.api.Authentication;
import org.eclipse.jetty.client.util.BasicAuthentication;
import org.eclipse.jetty.client.util.DigestAuthentication;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.camel.component.salesforce.SalesforceLoginConfig.DEFAULT_LOGIN_URL;

/**
 * Represents the component that manages {@link SalesforceEndpoint}.
 */
@Metadata(label = "verifiers", enums = "parameters,connectivity")
public class SalesforceComponent extends DefaultComponent implements VerifiableComponent, SSLContextParametersAware {

    static final int CONNECTION_TIMEOUT = 60000;
    static final Pattern SOBJECT_NAME_PATTERN = Pattern.compile("^.*[\\?&]sObjectName=([^&,]+).*$");
    static final String APEX_CALL_PREFIX = OperationName.APEX_CALL.value() + "/";

    private static final Logger LOG = LoggerFactory.getLogger(SalesforceComponent.class);

    @Metadata(description = "All authentication configuration in one nested bean, all properties set there can be set"
        + " directly on the component as well", label = "common,security")
    private SalesforceLoginConfig loginConfig;

    // allow fine grained login as well
    @Metadata(description = "URL of the Salesforce instance, by default set to " + DEFAULT_LOGIN_URL,
        label = "common,security", defaultValue = DEFAULT_LOGIN_URL, required = "true")
    private String loginUrl;

    @Metadata(description = "OAuth Consumer Key of the connected app configured in the Salesforce instance setup."
        + " Typically a connected app needs to be configured but one can be provided by installing a package.",
        label = "common,security", secret = true, required = "true")
    private String clientId;

    @Metadata(description = "OAuth Consumer Secret of the connected app configured in the Salesforce instance setup.",
        label = "common,security", secret = true)
    private String clientSecret;

    @Metadata(description = "Refresh token already obtained in the refresh token OAuth flow. One needs to setup a web"
        + " application and configure a callback URL to receive the refresh token, or configure using the builtin"
        + " callback at https://login.salesforce.com/services/oauth2/success or "
        + " https://test.salesforce.com/services/oauth2/success and then retrive the refresh_token from the URL at the"
        + " end of the flow. Note that in development organizations Salesforce allows hosting the callback web "
        + " application at localhost.",
        label = "common,security", secret = true)
    private String refreshToken;

    @Metadata(description = "Username used in OAuth flow to gain access to access token. It's easy to get started with" 
        + " password OAuth flow, but in general one should avoid it as it is deemed less secure than other flows.",
        label = "common,security",
        secret = true)
    private String userName;

    @Metadata(description = "Password used in OAuth flow to gain access to access token. It's easy to get started with"
        + " password OAuth flow, but in general one should avoid it as it is deemed less secure than other flows."
        + " Make sure that you append security token to the end of the password if using one.",
        label = "common,security", secret = true)
    private String password;

    @Metadata(description = "KeyStore parameters to use in OAuth JWT flow. The KeyStore should contain only one entry"
        + " with private key and certificate. Salesforce does not verify the certificate chain, so this can easily be"
        + " a selfsigned certificate. Make sure that you upload the certificate to the corresponding connected app.",
        label = "common,security", secret = true)
    private KeyStoreParameters keystore;

    @Metadata(description = "Explicit authentication method to be used, one of USERNAME_PASSWORD, REFRESH_TOKEN or JWT."
        + " Salesforce component can auto-determine the authentication method to use from the properties set, set this "
        + " property to eliminate any ambiguity.",
        label = "common,security", secret = false, enums = "USERNAME_PASSWORD,REFRESH_TOKEN,JWT")
    private AuthenticationType authenticationType;

    @Metadata(description = "If set to true prevents the component from authenticating to Salesforce with the start of"
        + " the component. You would generaly set this to the (default) false and authenticate early and be immediately"
        + " aware of any authentication issues.", defaultValue = "false", label = "common,security")
    private boolean lazyLogin;

    @Metadata(description = "Global endpoint configuration - use to set values that are common to all endpoints",
        label = "common,advanced")
    private SalesforceEndpointConfig config;

    @Metadata(description = "Used to set any properties that can be configured on the underlying HTTP client. Have a"
        + " look at properties of SalesforceHttpClient and the Jetty HttpClient for all available options.",
        label = "common,advanced")
    private Map<String, Object> httpClientProperties;

    @Metadata(description = "SSL parameters to use, see SSLContextParameters class for all available options.",
        label = "common,security")
    private SSLContextParameters sslContextParameters;
    @Metadata(description = "Enable usage of global SSL context parameters", label = "security", defaultValue = "false")
    private boolean useGlobalSslContextParameters;

    // Proxy host and port
    @Metadata(description = "Hostname of the HTTP proxy server to use.", label = "common,proxy")
    private String httpProxyHost;

    @Metadata(description = "Port number of the HTTP proxy server to use.", label = "common,proxy")
    private Integer httpProxyPort;

    @Metadata(description = "If set to true the configures the HTTP proxy to use as a SOCKS4 proxy.",
        defaultValue = "false", label = "common,proxy")
    private boolean isHttpProxySocks4;

    @Metadata(description = "If set to false disables the use of TLS when accessing the HTTP proxy.",
        defaultValue = "true", label = "common,proxy,security")
    private boolean isHttpProxySecure = true;

    @Metadata(description = "A list of addresses for which HTTP proxy server should be used.", label = "common,proxy")
    private Set<String> httpProxyIncludedAddresses;

    @Metadata(description = "A list of addresses for which HTTP proxy server should not be used.",
        label = "common,proxy")
    private Set<String> httpProxyExcludedAddresses;

    // Proxy basic authentication
    @Metadata(description = "Username to use to authenticate against the HTTP proxy server.",
        label = "common,proxy,security", secret = true)
    private String httpProxyUsername;

    @Metadata(description = "Password to use to authenticate against the HTTP proxy server.",
        label = "common,proxy,security", secret = true)
    private String httpProxyPassword;

    @Metadata(description = "Used in authentication against the HTTP proxy server, needs to match the URI of the proxy"
        + " server in order for the httpProxyUsername and httpProxyPassword to be used for authentication.",
        label = "common,proxy,security")
    private String httpProxyAuthUri;

    @Metadata(description = "Realm of the proxy server, used in preemptive Basic/Digest authentication methods against"
        + " the HTTP proxy server.", label = "common,proxy,security")
    private String httpProxyRealm;

    @Metadata(description = "If set to true Digest authentication will be used when authenticating to the HTTP proxy,"
        + "otherwise Basic authorization method will be used", defaultValue = "false", label = "common,proxy,security")
    private boolean httpProxyUseDigestAuth;

    @Metadata(description = "In what packages are the generated DTO classes. Typically the classes would be generated"
        + " using camel-salesforce-maven-plugin. Set it if using the generated DTOs to gain the benefit of using short "
        + " SObject names in parameters/header values.", label = "common")
    private String[] packages;

    // component state
    private SalesforceHttpClient httpClient;

    private SalesforceSession session;

    private Map<String, Class<?>> classMap;

    // Lazily created helper for consumer endpoints
    private SubscriptionHelper subscriptionHelper;

    public SalesforceComponent() {
    }

    public SalesforceComponent(CamelContext context) {
        super(context);
    }

    protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) throws Exception {
        // get Operation from remaining URI
        OperationName operationName = null;
        String topicName = null;
        String apexUrl = null;
        try {
            LOG.debug("Creating endpoint for: {}", remaining);
            if (remaining.startsWith(APEX_CALL_PREFIX)) {
                // extract APEX URL
                apexUrl = remaining.substring(APEX_CALL_PREFIX.length());
                remaining = OperationName.APEX_CALL.value();
            }
            operationName = OperationName.fromValue(remaining);
        } catch (IllegalArgumentException ex) {
            // if its not an operation name, treat is as topic name for consumer endpoints
            topicName = remaining;
        }

        // create endpoint config
        if (config == null) {
            config = new SalesforceEndpointConfig();
        }
        if (config.getHttpClient() == null) {
            // set the component's httpClient as default
            config.setHttpClient(httpClient);
        }

        // create a deep copy and map parameters
        final SalesforceEndpointConfig copy = config.copy();
        setProperties(copy, parameters);

        // set apexUrl in endpoint config
        if (apexUrl != null) {
            copy.setApexUrl(apexUrl);
        }

        final SalesforceEndpoint endpoint = new SalesforceEndpoint(uri, this, copy,
                operationName, topicName);

        // map remaining parameters to endpoint (specifically, synchronous)
        setProperties(endpoint, parameters);

        // if operation is APEX call, map remaining parameters to query params
        if (operationName == OperationName.APEX_CALL && !parameters.isEmpty()) {
            Map<String, Object> queryParams = new HashMap<String, Object>(copy.getApexQueryParams());

            // override component params with endpoint params
            queryParams.putAll(parameters);
            parameters.clear();

            copy.setApexQueryParams(queryParams);
        }

        return endpoint;
    }

    private Map<String, Class<?>> parsePackages() {
        Map<String, Class<?>> result = new HashMap<String, Class<?>>();
        Set<Class<?>> classes = getCamelContext().getPackageScanClassResolver().
                findImplementations(AbstractSObjectBase.class, packages);
        for (Class<?> aClass : classes) {
            // findImplementations also returns AbstractSObjectBase for some reason!!!
            if (AbstractSObjectBase.class != aClass) {
                result.put(aClass.getSimpleName(), aClass);
            }
        }

        return result;
    }

    @Override
    protected void doStart() throws Exception {
        if (loginConfig == null) {
            loginConfig = new SalesforceLoginConfig();
            loginConfig.setClientId(clientId);
            loginConfig.setClientSecret(clientSecret);
            loginConfig.setKeystore(keystore);
            loginConfig.setLazyLogin(lazyLogin);
            loginConfig.setLoginUrl(loginUrl);
            loginConfig.setPassword(password);
            loginConfig.setRefreshToken(refreshToken);
            loginConfig.setType(authenticationType);
            loginConfig.setUserName(userName);

            LOG.debug("Created login configuration: {}", loginConfig);
        } else {
            LOG.debug("Using shared login configuration: {}", loginConfig);
        }

        // create a Jetty HttpClient if not already set
        if (null == httpClient) {
            if (config != null && config.getHttpClient() != null) {
                httpClient = config.getHttpClient();
            } else {
                // set ssl context parameters if set
                SSLContextParameters contextParameters = sslContextParameters;
                if (contextParameters == null) {
                    contextParameters = retrieveGlobalSslContextParameters();
                }
                if (contextParameters == null) {
                    contextParameters = new SSLContextParameters();
                }
                final SslContextFactory sslContextFactory = new SslContextFactory();
                sslContextFactory.setSslContext(contextParameters.createSSLContext(getCamelContext()));

                httpClient = new SalesforceHttpClient(sslContextFactory);
                // default settings, use httpClientProperties to set other properties
                httpClient.setConnectTimeout(CONNECTION_TIMEOUT);
            }
        }

        // set HTTP client parameters
        if (httpClientProperties != null && !httpClientProperties.isEmpty()) {
            IntrospectionSupport.setProperties(getCamelContext().getTypeConverter(),
                httpClient, new HashMap<String, Object>(httpClientProperties));
        }

        // set HTTP proxy settings
        if (this.httpProxyHost != null && httpProxyPort != null) {
            Origin.Address proxyAddress = new Origin.Address(this.httpProxyHost, this.httpProxyPort);
            ProxyConfiguration.Proxy proxy; 
            if (isHttpProxySocks4) {
                proxy = new Socks4Proxy(proxyAddress, isHttpProxySecure);
            } else {
                proxy = new HttpProxy(proxyAddress, isHttpProxySecure);
            }
            if (httpProxyIncludedAddresses != null && !httpProxyIncludedAddresses.isEmpty()) {
                proxy.getIncludedAddresses().addAll(httpProxyIncludedAddresses);
            }
            if (httpProxyExcludedAddresses != null && !httpProxyExcludedAddresses.isEmpty()) {
                proxy.getExcludedAddresses().addAll(httpProxyExcludedAddresses);
            }
            httpClient.getProxyConfiguration().getProxies().add(proxy);
        }
        if (this.httpProxyUsername != null && httpProxyPassword != null) {

            StringHelper.notEmpty(httpProxyAuthUri, "httpProxyAuthUri");
            StringHelper.notEmpty(httpProxyRealm, "httpProxyRealm");

            final Authentication authentication;
            if (httpProxyUseDigestAuth) {
                authentication = new DigestAuthentication(new URI(httpProxyAuthUri),
                    httpProxyRealm, httpProxyUsername, httpProxyPassword);
            } else {
                authentication = new BasicAuthentication(new URI(httpProxyAuthUri),
                    httpProxyRealm, httpProxyUsername, httpProxyPassword);
            }
            httpClient.getAuthenticationStore().addAuthentication(authentication);
        }

        // support restarts
        if (this.session == null) {
            this.session = new SalesforceSession(getCamelContext(), httpClient, httpClient.getTimeout(), loginConfig);
        }
        // set session before calling start()
        httpClient.setSession(this.session);

        // start the Jetty client to initialize thread pool, etc.
        httpClient.start();

        // login at startup if lazyLogin is disabled
        if (!loginConfig.isLazyLogin()) {
            ServiceHelper.startService(session);
        }

        if (packages != null && packages.length > 0) {
            // parse the packages to create SObject name to class map
            classMap = parsePackages();
            LOG.info("Found {} generated classes in packages: {}", classMap.size(), Arrays.asList(packages));
        } else {
            // use an empty map to avoid NPEs later
            LOG.warn("Missing property packages, getSObject* operations will NOT work");
            classMap = new HashMap<String, Class<?>>(0);
        }

        if (subscriptionHelper != null) {
            ServiceHelper.startService(subscriptionHelper);
        }
    }

    @Override
    protected void doStop() throws Exception {
        if (classMap != null) {
            classMap.clear();
        }

        try {
            if (subscriptionHelper != null) {
                // shutdown all streaming connections
                // note that this is done in the component, and not in consumer
                ServiceHelper.stopService(subscriptionHelper);
                subscriptionHelper = null;
            }
            if (session != null && session.getAccessToken() != null) {
                try {
                    // logout of Salesforce
                    ServiceHelper.stopService(session);
                } catch (SalesforceException ignored) {
                }
            }
        } finally {
            if (httpClient != null) {
                // shutdown http client connections
                httpClient.stop();
                // destroy http client if it was created by the component
                if (config.getHttpClient() == null) {
                    httpClient.destroy();
                }
                httpClient = null;
            }
        }
    }

    public SubscriptionHelper getSubscriptionHelper() throws Exception {
        if (subscriptionHelper == null) {
            // lazily create subscription helper
            subscriptionHelper = new SubscriptionHelper(this);

            // also start the helper to connect to Salesforce
            ServiceHelper.startService(subscriptionHelper);
        }
        return subscriptionHelper;
    }

    public AuthenticationType getAuthenticationType() {
        return authenticationType;
    }

    public void setAuthenticationType(AuthenticationType authenticationType) {
        this.authenticationType = authenticationType;
    }

    public SalesforceLoginConfig getLoginConfig() {
        return loginConfig;
    }

    public void setLoginConfig(SalesforceLoginConfig loginConfig) {
        this.loginConfig = loginConfig;
    }

    public void setLoginUrl(String loginUrl) {
        this.loginUrl = loginUrl;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;

    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public void setKeystore(final KeyStoreParameters keystore) {
        this.keystore = keystore;
    }

    public KeyStoreParameters getKeystore() {
        return keystore;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isLazyLogin() {
        return lazyLogin;
    }

    public void setLazyLogin(boolean lazyLogin) {
        this.lazyLogin = lazyLogin;
    }

    public SalesforceEndpointConfig getConfig() {
        return config;
    }

    public void setConfig(SalesforceEndpointConfig config) {
        this.config = config;
    }

    public Map<String, Object> getHttpClientProperties() {
        return httpClientProperties;
    }

    public void setHttpClientProperties(Map<String, Object> httpClientProperties) {
        this.httpClientProperties = httpClientProperties;
    }

    public SSLContextParameters getSslContextParameters() {
        return sslContextParameters;
    }

    public void setSslContextParameters(SSLContextParameters sslContextParameters) {
        this.sslContextParameters = sslContextParameters;
    }

    @Override
    public boolean isUseGlobalSslContextParameters() {
        return this.useGlobalSslContextParameters;
    }

    @Override
    public void setUseGlobalSslContextParameters(boolean useGlobalSslContextParameters) {
        this.useGlobalSslContextParameters = useGlobalSslContextParameters;
    }

    public String getHttpProxyHost() {
        return httpProxyHost;
    }

    public void setHttpProxyHost(String httpProxyHost) {
        this.httpProxyHost = httpProxyHost;
    }

    public Integer getHttpProxyPort() {
        return httpProxyPort;
    }

    public void setHttpProxyPort(Integer httpProxyPort) {
        this.httpProxyPort = httpProxyPort;
    }

    public String getHttpProxyUsername() {
        return httpProxyUsername;
    }

    public void setHttpProxyUsername(String httpProxyUsername) {
        this.httpProxyUsername = httpProxyUsername;
    }

    public String getHttpProxyPassword() {
        return httpProxyPassword;
    }

    public void setHttpProxyPassword(String httpProxyPassword) {
        this.httpProxyPassword = httpProxyPassword;
    }

    public boolean isHttpProxySocks4() {
        return isHttpProxySocks4;
    }

    public void setIsHttpProxySocks4(boolean isHttpProxySocks4) {
        this.isHttpProxySocks4 = isHttpProxySocks4;
    }

    public boolean isHttpProxySecure() {
        return isHttpProxySecure;
    }

    public void setIsHttpProxySecure(boolean isHttpProxySecure) {
        this.isHttpProxySecure = isHttpProxySecure;
    }

    public Set<String> getHttpProxyIncludedAddresses() {
        return httpProxyIncludedAddresses;
    }

    public void setHttpProxyIncludedAddresses(Set<String> httpProxyIncludedAddresses) {
        this.httpProxyIncludedAddresses = httpProxyIncludedAddresses;
    }

    public Set<String> getHttpProxyExcludedAddresses() {
        return httpProxyExcludedAddresses;
    }

    public void setHttpProxyExcludedAddresses(Set<String> httpProxyExcludedAddresses) {
        this.httpProxyExcludedAddresses = httpProxyExcludedAddresses;
    }

    public String getHttpProxyAuthUri() {
        return httpProxyAuthUri;
    }

    public void setHttpProxyAuthUri(String httpProxyAuthUri) {
        this.httpProxyAuthUri = httpProxyAuthUri;
    }

    public String getHttpProxyRealm() {
        return httpProxyRealm;
    }

    public void setHttpProxyRealm(String httpProxyRealm) {
        this.httpProxyRealm = httpProxyRealm;
    }

    public boolean isHttpProxyUseDigestAuth() {
        return httpProxyUseDigestAuth;
    }

    public void setHttpProxyUseDigestAuth(boolean httpProxyUseDigestAuth) {
        this.httpProxyUseDigestAuth = httpProxyUseDigestAuth;
    }

    public String[] getPackages() {
        return packages;
    }

    public void setPackages(String[] packages) {
        this.packages = packages;
    }

    public void setPackages(String packages) {
        // split using comma
        if (packages != null) {
            setPackages(packages.split(","));
        }
    }

    public SalesforceSession getSession() {
        return session;
    }

    public Map<String, Class<?>> getClassMap() {
        return classMap;
    }

    public ComponentVerifier getVerifier() {
        return new SalesforceComponentVerifier(this);
    }
}
