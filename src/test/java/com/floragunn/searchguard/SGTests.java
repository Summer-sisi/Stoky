/*
 * Copyright 2015 floragunn UG (haftungsbeschränkt)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package com.floragunn.searchguard;

import io.netty.handler.ssl.OpenSsl;

import java.net.InetSocketAddress;
import java.util.Iterator;

import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthRequest;
import org.elasticsearch.action.admin.cluster.node.info.NodesInfoRequest;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesResponse;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.action.support.WriteRequest.RefreshPolicy;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.cluster.health.ClusterHealthStatus;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.ThreadContext.StoredContext;
import org.elasticsearch.node.Node;
import org.elasticsearch.node.PluginAwareNode;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import com.floragunn.searchguard.action.configupdate.ConfigUpdateAction;
import com.floragunn.searchguard.action.configupdate.ConfigUpdateRequest;
import com.floragunn.searchguard.action.configupdate.ConfigUpdateResponse;
import com.floragunn.searchguard.ssl.SearchGuardSSLPlugin;
import com.floragunn.searchguard.ssl.util.SSLConfigConstants;
import com.floragunn.searchguard.support.Base64Helper;
import com.floragunn.searchguard.support.ReflectionHelper;

public class SGTests extends AbstractUnitTest {

    static {
        System.setProperty("sg.nowarn.client","true");
    }
    
    @Rule
    public final ExpectedException thrown = ExpectedException.none();

    protected boolean allowOpenSSL = Boolean.parseBoolean(System.getenv("SG_ALLOW_OPENSSL"));

    @Test
    public void testEnsureOpenSSLAvailability() {
        
        if(allowOpenSSL) {
            Assert.assertTrue(String.valueOf(OpenSsl.unavailabilityCause()), OpenSsl.isAvailable());
        }
    }
    
    @Test
    public void testDiscoveryWithoutInitialization() throws Exception {
        
        final Settings settings = Settings.builder().put("searchguard.ssl.transport.enabled", true)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS, "node-0")
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.transport.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                .put("searchguard.ssl.transport.enforce_hostname_verification", false)
                .put("searchguard.ssl.transport.resolve_hostname", false)
                .putArray("searchguard.authcz.admin_dn", "cn=dummy_to_activate_search_guard_plugin")
                .build();

        startES(settings);
        Assert.assertEquals(3, client().admin().cluster().health(new ClusterHealthRequest().waitForGreenStatus()).actionGet().getNumberOfNodes());
        Assert.assertEquals(ClusterHealthStatus.GREEN, client().admin().cluster().health(new ClusterHealthRequest().waitForGreenStatus()).actionGet().getStatus());
        //Assert.assertEquals(3, client().admin().cluster().nodesInfo(new NodesInfoRequest().all()).actionGet().getNodes().size());
    }

    @Test
    public void testNodeClientDisallowedWithNonServerCertificate() throws Exception {
        final Settings settings = Settings.builder().put("searchguard.ssl.transport.enabled", true)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS, "node-0")
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.transport.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                .put("searchguard.ssl.transport.enforce_hostname_verification", false)
                .put("searchguard.ssl.transport.resolve_hostname", false)
                .build();
        
        final Settings esSettings = Settings.builder().put(settings)
                .putArray("searchguard.authcz.admin_dn", "cn=dummy_to_activate_search_guard_plugin").build();

        startES(esSettings);
        Assert.assertEquals(3, client().admin().cluster().health(new ClusterHealthRequest().waitForGreenStatus()).actionGet().getNumberOfNodes());
        Assert.assertEquals(ClusterHealthStatus.GREEN, client().admin().cluster().health(new ClusterHealthRequest().waitForGreenStatus()).actionGet().getStatus());
  
        
        final Settings tcSettings = Settings.builder().put("cluster.name", clustername).put("node.client", true).put("path.home", ".")
                .put(settings)
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("kirk-keystore.jks"))
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS,"kirk")
                .build();

        log.debug("Start node client");
        
        try (Node node = new PluginAwareNode(tcSettings, SearchGuardSSLPlugin.class).start()) {
            Assert.assertEquals(1, node.client().admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());
            Assert.assertEquals(3, client().admin().cluster().health(new ClusterHealthRequest().waitForGreenStatus()).actionGet().getNumberOfNodes());
            
        }
    }
    
    @Test
    public void testNodeClientDisallowedWithNonServerCertificateFull() throws Exception {
        final Settings settings = Settings.builder().put("searchguard.ssl.transport.enabled", true)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS, "node-0")
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.transport.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                .put("searchguard.ssl.transport.enforce_hostname_verification", false)
                .put("searchguard.ssl.transport.resolve_hostname", false)
                .putArray("searchguard.authcz.admin_dn", "cn=dummy_to_activate_search_guard_plugin")
                .build();

        startES(settings);
        Assert.assertEquals(3, client().admin().cluster().health(new ClusterHealthRequest().waitForGreenStatus()).actionGet().getNumberOfNodes());
        Assert.assertEquals(ClusterHealthStatus.GREEN, client().admin().cluster().health(new ClusterHealthRequest().waitForGreenStatus()).actionGet().getStatus());
  
        
        final Settings tcSettings = Settings.builder().put("cluster.name", clustername)
                 .put("path.home", ".")
                .put(settings)
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("kirk-keystore.jks"))
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS,"kirk")
                .build();

        log.debug("Start node client");
        
        //Node und Transportclient: SG Plugin required? or SSL only ok?
        
        try (Node node = new PluginAwareNode(tcSettings, SearchGuardSSLPlugin.class, SearchGuardPlugin.class).start()) {
            Assert.assertEquals(1, node.client().admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());
            Assert.assertEquals(3, client().admin().cluster().health(new ClusterHealthRequest().waitForGreenStatus()).actionGet().getNumberOfNodes());
            
        }
    }
    
    @Test
    @Ignore
    public void testNodeClientAllowedWithServerCertificate() throws Exception {
        final Settings settings = Settings.builder().put("searchguard.ssl.transport.enabled", true)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS, "node-0")
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.transport.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                .put("searchguard.ssl.transport.enforce_hostname_verification", false)
                .put("searchguard.ssl.transport.resolve_hostname", false).build();

        startES(settings);
        Assert.assertEquals(3, client().admin().cluster().health(new ClusterHealthRequest().waitForGreenStatus()).actionGet().getNumberOfNodes());
        Assert.assertEquals(ClusterHealthStatus.GREEN, client().admin().cluster().health(new ClusterHealthRequest().waitForGreenStatus()).actionGet().getStatus());
  
        
        final Settings tcSettings = Settings.builder().put("cluster.name", clustername).put("node.client", true).put("path.home", ".")
                .put(settings)
                .build();

        log.debug("Start node client");
        
        try (Node node = new PluginAwareNode(tcSettings, SearchGuardSSLPlugin.class).start()) {
            Assert.assertEquals(4, node.client().admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());
            Assert.assertEquals(4, client().admin().cluster().health(new ClusterHealthRequest().waitForGreenStatus()).actionGet().getNumberOfNodes());
            
        }
    }
    
    @Test
    public void ensureInitViaRestWontWork() throws Exception {
        final Settings settings = Settings.builder().put("searchguard.ssl.transport.enabled", true)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS, "node-0")
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.transport.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                .put("searchguard.ssl.transport.enforce_hostname_verification", false)
                .put("searchguard.ssl.transport.resolve_hostname", false)
                .put("searchguard.ssl.http.clientauth_mode","REQUIRE")
                .put("searchguard.ssl.http.enabled",true)
                .put("searchguard.ssl.http.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.http.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                .putArray("searchguard.authcz.admin_dn", "CN=kirk,OU=client,O=client,l=tEst, C=De")
                
                /*
                searchguard.authcz.impersonation_dn:
                  "cn=technical_user1,ou=Test,ou=ou,dc=company,dc=com":
                    - '*'
                  "cn=webuser,ou=IT,ou=IT,dc=company,dc=com":
                    - 'kirk'
                    - 'user1'
                 
                 */
                
                .putArray("searchguard.authcz.impersonation_dn.CN=spock,OU=client,O=client,L=Test,C=DE", "worf")
                .build();
        
        startES(settings);
        
        this.enableHTTPClientSSL = true;
        this.trustHTTPServerCertificate = true;
        this.sendHTTPClientCertificate = true;
        Assert.assertEquals(HttpStatus.SC_SERVICE_UNAVAILABLE, executePutRequest("searchguard/config/0", "{}",new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("___", ""))).getStatusCode());
        
        this.keystore = "kirk-keystore.jks";
        Assert.assertEquals(HttpStatus.SC_SERVICE_UNAVAILABLE, executePutRequest("searchguard/config/0", "{}",new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("___", ""))).getStatusCode());

    }
    
    @Test
    public void testHTTPClientCert() throws Exception {
        final Settings settings = Settings.builder().put("searchguard.ssl.transport.enabled", true)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS, "node-0")
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.transport.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                .put("searchguard.ssl.transport.enforce_hostname_verification", false)
                .put("searchguard.ssl.transport.resolve_hostname", false)
                .put("searchguard.ssl.http.clientauth_mode","REQUIRE")
                .put("searchguard.ssl.http.enabled",true)
                .put("searchguard.ssl.http.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.http.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                .putArray("searchguard.authcz.admin_dn", "CN=kirk,OU=client,O=client,l=tEst, C=De")
                
                /*
                searchguard.authcz.impersonation_dn:
                  "cn=technical_user1,ou=Test,ou=ou,dc=company,dc=com":
                    - '*'
                  "cn=webuser,ou=IT,ou=IT,dc=company,dc=com":
                    - 'kirk'
                    - 'user1'
                 
                 */
                
                .putArray("searchguard.authcz.impersonation_dn.CN=spock,OU=client,O=client,L=Test,C=DE", "worf")
                .build();
        
        startES(settings);
        
        Settings tcSettings = Settings.builder().put("cluster.name", clustername)
                .put(settings)
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("kirk-keystore.jks"))
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS,"kirk")
                .put("path.home", ".")
                .build();

        try (TransportClient tc = TransportClient.builder().settings(tcSettings).addPlugin(SearchGuardSSLPlugin.class).addPlugin(SearchGuardPlugin.class).build()) {
            
            log.debug("Start transport client to init");
            
            tc.addTransportAddress(new InetSocketTransportAddress(new InetSocketAddress(nodeHost, nodePort)));
            
            Assert.assertEquals(3, tc.admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());

            tc.admin().indices().create(new CreateIndexRequest("searchguard")).actionGet();
            //tc.index(new IndexRequest("searchguard").type("dummy").id("0").source("", readYamlContent("sg_config.yml"))).actionGet();
            
            //Thread.sleep(5000);
            
            tc.index(new IndexRequest("searchguard").type("config").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("config", readYamlContent("sg_config_clientcert.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("internalusers").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("internalusers", readYamlContent("sg_internal_users.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("roles").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("roles", readYamlContent("sg_roles.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("rolesmapping").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("rolesmapping", readYamlContent("sg_roles_mapping.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("actiongroups").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("actiongroups", readYamlContent("sg_action_groups.yml"))).actionGet();
            
            System.out.println("------- End INIT ---------");
            
            tc.index(new IndexRequest("vulcangov").type("kolinahr").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("vulcangov").type("secrets").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("vulcangov").type("planet").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            
            tc.index(new IndexRequest("starfleet").type("ships").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("starfleet").type("captains").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("starfleet").type("public").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            
            tc.index(new IndexRequest("starfleet_academy").type("students").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("starfleet_academy").type("alumni").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            
            tc.index(new IndexRequest("starfleet_library").type("public").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("starfleet_library").type("administration").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            
            tc.index(new IndexRequest("klingonempire").type("ships").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("klingonempire").type("praxis").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            
            tc.index(new IndexRequest("public").type("legends").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("public").type("hall_of_fame").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("public").type("hall_of_fame").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":2}")).actionGet();
            
            tc.admin().indices().aliases(new IndicesAliasesRequest().addAlias("sf", "starfleet","starfleet_academy","starfleet_library")).actionGet();
            tc.admin().indices().aliases(new IndicesAliasesRequest().addAlias("nonsf", "klingonempire","vulcangov")).actionGet();
            tc.admin().indices().aliases(new IndicesAliasesRequest().addAlias("unrestricted", "public")).actionGet();
            
            ConfigUpdateResponse cur = tc.execute(ConfigUpdateAction.INSTANCE, new ConfigUpdateRequest(new String[]{"config","roles","rolesmapping","internalusers","actiongroups"})).actionGet();
            Assert.assertEquals(3, cur.getNodes().size());
        }
 
        
        this.enableHTTPClientSSL = true;
        this.trustHTTPServerCertificate = true;
        this.sendHTTPClientCertificate = true;
        this.keystore = "spock-keystore.jks";
        Assert.assertEquals(HttpStatus.SC_OK, executeGetRequest("_search").getStatusCode());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, executePutRequest("searchguard/config/0", "{}").getStatusCode());
        
        this.keystore = "kirk-keystore.jks";
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, executePutRequest("searchguard/config/0", "{}").getStatusCode());
        HttpResponse res;
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, (res = executeGetRequest("_searchguard/authinfo")).getStatusCode());
    }

    @Test
    public void testHTTPBasic() throws Exception {

        final Settings settings = Settings.builder().put("searchguard.ssl.transport.enabled", true)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS, "node-0")
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.transport.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                .put("searchguard.ssl.transport.enforce_hostname_verification", false)
                .put("searchguard.ssl.transport.resolve_hostname", false)
                .putArray("searchguard.authcz.admin_dn", "CN=kirk,OU=client,O=client,l=tEst, C=De")
                
                /*
                searchguard.authcz.impersonation_dn:
                  "cn=technical_user1,ou=Test,ou=ou,dc=company,dc=com":
                    - '*'
                  "cn=webuser,ou=IT,ou=IT,dc=company,dc=com":
                    - 'kirk'
                    - 'user1'
                 
                 */
                
                .putArray("searchguard.authcz.impersonation_dn.CN=spock,OU=client,O=client,L=Test,C=DE", "worf")
                .build();
        
        startES(settings);

        Settings tcSettings = Settings.builder().put("cluster.name", clustername)
                .put(settings)
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("kirk-keystore.jks"))
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS,"kirk")
                .put("path.home", ".").build();

        try (TransportClient tc = TransportClient.builder().settings(tcSettings).addPlugin(SearchGuardSSLPlugin.class).addPlugin(SearchGuardPlugin.class).build()) {
            
            log.debug("Start transport client to init");
            
            tc.addTransportAddress(new InetSocketTransportAddress(new InetSocketAddress(nodeHost, nodePort)));
            Assert.assertEquals(3, tc.admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());

            tc.admin().indices().create(new CreateIndexRequest("searchguard")).actionGet();
            //tc.index(new IndexRequest("searchguard").type("dummy").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("", readYamlContent("sg_config.yml"))).actionGet();
            
            //Thread.sleep(5000);
            
            tc.index(new IndexRequest("searchguard").type("config").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("config", readYamlContent("sg_config.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("internalusers").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("internalusers", readYamlContent("sg_internal_users.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("roles").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("roles", readYamlContent("sg_roles.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("rolesmapping").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("rolesmapping", readYamlContent("sg_roles_mapping.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("actiongroups").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("actiongroups", readYamlContent("sg_action_groups.yml"))).actionGet();
            
            tc.index(new IndexRequest("vulcangov").type("kolinahr").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("vulcangov").type("secrets").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("vulcangov").type("planet").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            
            tc.index(new IndexRequest("starfleet").type("ships").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("starfleet").type("captains").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("starfleet").type("public").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            
            tc.index(new IndexRequest("starfleet_academy").type("students").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("starfleet_academy").type("alumni").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            
            tc.index(new IndexRequest("starfleet_library").type("public").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("starfleet_library").type("administration").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            
            tc.index(new IndexRequest("klingonempire").type("ships").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("klingonempire").type("praxis").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            
            tc.index(new IndexRequest("public").type("legends").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("public").type("hall_of_fame").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("public").type("hall_of_fame").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":2}")).actionGet();
            
            tc.admin().indices().aliases(new IndicesAliasesRequest().addAlias("sf", "starfleet","starfleet_academy","starfleet_library")).actionGet();
            tc.admin().indices().aliases(new IndicesAliasesRequest().addAlias("nonsf", "klingonempire","vulcangov")).actionGet();
            tc.admin().indices().aliases(new IndicesAliasesRequest().addAlias("unrestricted", "public")).actionGet();
            
            ConfigUpdateResponse cur = tc.execute(ConfigUpdateAction.INSTANCE, new ConfigUpdateRequest(new String[]{"config","roles","rolesmapping","internalusers","actiongroups"})).actionGet();
            Assert.assertEquals(3, cur.getNodes().size());
            System.out.println(cur.getNodesMap());
        }
        
        System.out.println("------- End INIT ---------");
        
        Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, executeGetRequest("").getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, executeGetRequest("", new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("worf", "worf"))).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, executeGetRequest("", new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("nagilum", "nagilum"))).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_NOT_FOUND, executeGetRequest("searchguard/config/0", new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("nagilum", "nagilum"))).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_NOT_FOUND, executeGetRequest("xxxxyyyy/config/0", new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("nagilum", "nagilum"))).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, executeGetRequest("", new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("abc", "abc:abc"))).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, executeGetRequest("", new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("userwithnopassword", ""))).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, executeGetRequest("", new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("userwithblankpassword", ""))).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, executeGetRequest("", new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("worf", "wrongpasswd"))).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, executeGetRequest("", new BasicHeader("Authorization", "Basic "+"wrongheader")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, executeGetRequest("", new BasicHeader("Authorization", "Basic ")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, executeGetRequest("", new BasicHeader("Authorization", "Basic")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, executeGetRequest("", new BasicHeader("Authorization", "")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, executeGetRequest("", new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("picard", "picard"))).getStatusCode());

        for(int i=0; i< 10; i++) {
            Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, executeGetRequest("", new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("worf", "wrongpasswd"))).getStatusCode());
        }

        Assert.assertEquals(HttpStatus.SC_OK, executePutRequest("/theindex","{}",new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("theindexadmin", "theindexadmin"))).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, executeGetRequest("starfleet/_search", new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("worf", "worf"))).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, executeGetRequest("_search", new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("worf", "worf"))).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, executeGetRequest("starfleet/ships/_search?pretty", new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("worf", "worf"))).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, executeDeleteRequest("searchguard/", new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("worf", "worf"))).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, executePostRequest("/searchguard/_close", null,new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("worf", "worf"))).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, executePostRequest("/searchguard/_upgrade", null,new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("worf", "worf"))).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, executePutRequest("/searchguard/_mapping/config","{}",new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("worf", "worf"))).getStatusCode());

        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, executeGetRequest("searchguard/", new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("worf", "worf"))).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, executePutRequest("searchguard/config/2", "{}",new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("worf", "worf"))).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, executeGetRequest("searchguard/config/0",new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("worf", "worf"))).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, executeDeleteRequest("searchguard/config/0",new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("worf", "worf"))).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, executePutRequest("searchguard/config/0","{}",new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("worf", "worf"))).getStatusCode());
        
        HttpResponse resc = executeGetRequest("_cat/indices/public",new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("bug108", "nagilum")));
        Assert.assertTrue(resc.getBody().contains("green"));
        Assert.assertEquals(HttpStatus.SC_OK, resc.getStatusCode());
        
        
//all
        
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, executePutRequest("_mapping/config","{\"i\" : [\"4\"]}",new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("worf", "worf"))).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, executePostRequest("searchguard/_mget","{\"ids\" : [\"0\"]}",new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("worf", "worf"))).getStatusCode());
        
        Assert.assertEquals(HttpStatus.SC_OK, executeGetRequest("starfleet/ships/_search?pretty", new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("worf", "worf"))).getStatusCode());

        try (TransportClient tc = TransportClient.builder().settings(tcSettings).addPlugin(SearchGuardSSLPlugin.class).addPlugin(SearchGuardPlugin.class).build()) {
            
            log.debug("Start transport client to init 2");
            
            tc.addTransportAddress(new InetSocketTransportAddress(new InetSocketAddress(nodeHost, nodePort)));            

            tc.index(new IndexRequest("searchguard").type("roles").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("roles", readYamlContent("sg_roles_deny.yml"))).actionGet();
            ConfigUpdateResponse cur = tc.execute(ConfigUpdateAction.INSTANCE, new ConfigUpdateRequest(new String[]{"roles"})).actionGet();
            Assert.assertEquals(3, cur.getNodes().size());
        }
        
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, executeGetRequest("starfleet/ships/_search?pretty", new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("worf", "worf"))).getStatusCode());

        try (TransportClient tc = TransportClient.builder().settings(tcSettings).addPlugin(SearchGuardSSLPlugin.class).addPlugin(SearchGuardPlugin.class).build()) {
            
            log.debug("Start transport client to init 3");
            
            tc.addTransportAddress(new InetSocketTransportAddress(new InetSocketAddress(nodeHost, nodePort)));            
            
            tc.index(new IndexRequest("searchguard").type("roles").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("roles", readYamlContent("sg_roles.yml"))).actionGet();
            ConfigUpdateResponse cur = tc.execute(ConfigUpdateAction.INSTANCE, new ConfigUpdateRequest(new String[]{"roles"})).actionGet();
            Assert.assertEquals(3, cur.getNodes().size());
        }
        
        Assert.assertEquals(HttpStatus.SC_OK, executeGetRequest("starfleet/ships/_search?pretty", new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("worf", "worf"))).getStatusCode());
        HttpResponse res = executeGetRequest("_search?pretty", new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("nagilum", "nagilum")));
        Assert.assertEquals(HttpStatus.SC_OK, res.getStatusCode());
        Assert.assertTrue(res.getBody().contains("\"total\" : 15"));
        Assert.assertTrue(!res.getBody().contains("searchguard"));
        
        res = executeGetRequest("_nodes/stats?pretty", new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("nagilum", "nagilum")));
        Assert.assertEquals(HttpStatus.SC_OK, res.getStatusCode());
        Assert.assertTrue(res.getBody().contains("total_in_bytes"));
        Assert.assertTrue(res.getBody().contains("max_file_descriptors"));
        Assert.assertTrue(res.getBody().contains("buffer_pools"));
        Assert.assertFalse(res.getBody().contains("\"nodes\" : { }"));
    }
    
    
    @Test
    public void testConfigHotReload() throws Exception {

        final Settings settings = Settings.builder().put("searchguard.ssl.transport.enabled", true)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS, "node-0")
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.transport.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                .put("searchguard.ssl.transport.enforce_hostname_verification", false)
                .put("searchguard.ssl.transport.resolve_hostname", false)
                .putArray("searchguard.authcz.admin_dn", "CN=kirk,OU=client,O=client,l=tEst, C=De")
                
                /*
                searchguard.authcz.impersonation_dn:
                  "cn=technical_user1,ou=Test,ou=ou,dc=company,dc=com":
                    - '*'
                  "cn=webuser,ou=IT,ou=IT,dc=company,dc=com":
                    - 'kirk'
                    - 'user1'
                 
                 */
                
                .putArray("searchguard.authcz.impersonation_dn.CN=spock,OU=client,O=client,L=Test,C=DE", "worf")
                .build();
        
        startES(settings);

        Settings tcSettings = Settings.builder().put("cluster.name", clustername)
                .put(settings)
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("kirk-keystore.jks"))
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS,"kirk")
                .put("path.home", ".").build();

        try (TransportClient tc = TransportClient.builder().settings(tcSettings).addPlugin(SearchGuardSSLPlugin.class).addPlugin(SearchGuardPlugin.class).build()) {
            
            log.debug("Start transport client to init");
            
            tc.addTransportAddress(new InetSocketTransportAddress(new InetSocketAddress(nodeHost, nodePort)));
            Assert.assertEquals(3, tc.admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());

            tc.admin().indices().create(new CreateIndexRequest("searchguard")).actionGet();
            //tc.index(new IndexRequest("searchguard").type("dummy").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("dummy", readYamlContent("sg_config.yml"))).actionGet();
            
            //Thread.sleep(5000);
            
            tc.index(new IndexRequest("searchguard").type("config").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("config", readYamlContent("sg_config.yml"))).actionGet();
            
            //Thread.sleep(500000);
            

            tc.index(new IndexRequest("searchguard").type("internalusers").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("internalusers", readYamlContent("sg_internal_users.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("roles").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("roles", readYamlContent("sg_roles.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("rolesmapping").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("rolesmapping", readYamlContent("sg_roles_mapping.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("actiongroups").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("actiongroups", readYamlContent("sg_action_groups.yml"))).actionGet();

            ConfigUpdateResponse cur = tc.execute(ConfigUpdateAction.INSTANCE, new ConfigUpdateRequest(new String[]{"config","roles","rolesmapping","internalusers","actiongroups"})).actionGet();
            Assert.assertEquals(3, cur.getNodes().size());

        }
               
        
        BasicHeader spock = new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("spock", "spock"));
          
        for (Iterator iterator = httpAdresses.iterator(); iterator.hasNext();) {
            InetSocketTransportAddress inetSocketTransportAddress = (InetSocketTransportAddress) iterator.next();
            HttpResponse res = executeRequest(new HttpGet("http://"+inetSocketTransportAddress.getHost()+":"+inetSocketTransportAddress.getPort() + "/" + "_searchguard/authinfo?pretty=true"), spock);
            Assert.assertTrue(res.getBody().contains("spock"));
            Assert.assertFalse(res.getBody().contains("additionalrole"));
            Assert.assertTrue(res.getBody().contains("vulcan"));
        }
        
        try (TransportClient tc = TransportClient.builder().settings(tcSettings).addPlugin(SearchGuardSSLPlugin.class).addPlugin(SearchGuardPlugin.class).build()) {
            
            log.debug("Start transport client to init");
            
            tc.addTransportAddress(new InetSocketTransportAddress(new InetSocketAddress(nodeHost, nodePort)));

            Assert.assertEquals(3, tc.admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());
            tc.index(new IndexRequest("searchguard").type("internalusers").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("internalusers", readYamlContent("sg_internal_users_spock_add_roles.yml"))).actionGet();
            ConfigUpdateResponse cur = tc.execute(ConfigUpdateAction.INSTANCE, new ConfigUpdateRequest(new String[]{"config","roles","rolesmapping","internalusers","actiongroups"})).actionGet();
            Assert.assertEquals(3, cur.getNodes().size());   
        } 
        
        for (Iterator iterator = httpAdresses.iterator(); iterator.hasNext();) {
            InetSocketTransportAddress inetSocketTransportAddress = (InetSocketTransportAddress) iterator.next();
            log.debug("http://"+inetSocketTransportAddress.getHost()+":"+inetSocketTransportAddress.getPort());
            HttpResponse res = executeRequest(new HttpGet("http://"+inetSocketTransportAddress.getHost()+":"+inetSocketTransportAddress.getPort() + "/" + "_searchguard/authinfo?pretty=true"), spock);
            Assert.assertTrue(res.getBody().contains("spock"));
            Assert.assertTrue(res.getBody().contains("additionalrole1"));
            Assert.assertTrue(res.getBody().contains("additionalrole2"));
            Assert.assertFalse(res.getBody().contains("starfleet"));
        }
        
        try (TransportClient tc = TransportClient.builder().settings(tcSettings).addPlugin(SearchGuardSSLPlugin.class).addPlugin(SearchGuardPlugin.class).build()) {
            
            log.debug("Start transport client to init");
            
            tc.addTransportAddress(new InetSocketTransportAddress(new InetSocketAddress(nodeHost, nodePort)));

            Assert.assertEquals(3, tc.admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());
            tc.index(new IndexRequest("searchguard").type("config").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("config", readYamlContent("sg_config_host.yml"))).actionGet();
            ConfigUpdateResponse cur = tc.execute(ConfigUpdateAction.INSTANCE, new ConfigUpdateRequest(new String[]{"config","roles","rolesmapping","internalusers","actiongroups"})).actionGet();
            Assert.assertEquals(3, cur.getNodes().size());   
        } 
        
        for (Iterator iterator = httpAdresses.iterator(); iterator.hasNext();) {
            InetSocketTransportAddress inetSocketTransportAddress = (InetSocketTransportAddress) iterator.next();
            HttpResponse res = executeRequest(new HttpGet("http://"+inetSocketTransportAddress.getHost()+":"+inetSocketTransportAddress.getPort() + "/" + "_searchguard/authinfo?pretty=true"));
            log.debug(res.getBody());
            Assert.assertTrue(res.getBody().contains("sg_role_host1"));
            Assert.assertTrue(res.getBody().contains("sg_role_host2"));
            Assert.assertTrue(res.getBody().contains("sg_host_127.0.0.1"));
            Assert.assertTrue(res.getBody().contains("roles=[]"));
            Assert.assertEquals(200, res.getStatusCode());
        }
    }
    
    @Test
    public void testCreateIndex() throws Exception {

        final Settings settings = Settings.builder().put("searchguard.ssl.transport.enabled", true)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS, "node-0")
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.transport.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                .put("searchguard.ssl.transport.enforce_hostname_verification", false)
                .put("searchguard.ssl.transport.resolve_hostname", false)
                .putArray("searchguard.authcz.admin_dn", "CN=kirk,OU=client,O=client,l=tEst, C=De")
                //.put("index.number_of_shards", 3)
                //.put("index.number_of_replicas", 0)
                .build();
        
        startES(settings);

        Settings tcSettings = Settings.builder().put("cluster.name", clustername)
                .put(settings)
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("kirk-keystore.jks"))
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS,"kirk")
                .put("path.home", ".").build();

        try (TransportClient tc = TransportClient.builder().settings(tcSettings).addPlugin(SearchGuardSSLPlugin.class).addPlugin(SearchGuardPlugin.class).build()) {
            
            log.debug("Start transport client to init");
            
            tc.addTransportAddress(new InetSocketTransportAddress(new InetSocketAddress(nodeHost, nodePort)));

            Assert.assertEquals("Expected 3 nodes", 3, tc.admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());

            tc.admin().indices().create(new CreateIndexRequest("searchguard")).actionGet();
            tc.index(new IndexRequest("searchguard").type("dummy").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("", readYamlContent("sg_config.yml"))).actionGet();
            
            //Thread.sleep(5000);
            
            tc.index(new IndexRequest("searchguard").type("config").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("config", readYamlContent("sg_config.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("internalusers").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("internalusers", readYamlContent("sg_internal_users.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("roles").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("roles", readYamlContent("sg_roles.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("rolesmapping").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("rolesmapping", readYamlContent("sg_roles_mapping.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("actiongroups").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("actiongroups", readYamlContent("sg_action_groups.yml"))).actionGet();
            
            System.out.println("------- End INIT ---------");
                     
            tc.index(new IndexRequest("starfleet").type("ships").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("starfleet").type("captains").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("starfleet").type("public").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            
            tc.index(new IndexRequest("starfleet_academy").type("students").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("starfleet_academy").type("alumni").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();           
            IndicesAliasesResponse r = tc.admin().indices().aliases(new IndicesAliasesRequest().addAlias("sf", "starfleet","starfleet_academy")).actionGet();
            Assert.assertTrue("Alias creation not acknowledged", r.isAcknowledged());
            
            ConfigUpdateResponse cur = tc.execute(ConfigUpdateAction.INSTANCE, new ConfigUpdateRequest(new String[]{"config","roles","rolesmapping","internalusers","actiongroups"})).actionGet();
            Assert.assertEquals(3, cur.getNodes().size());
        }
              
        HttpResponse res;
        Assert.assertEquals("Unable to create index 'nag'", HttpStatus.SC_OK, executePutRequest("nag1", null, new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("nagilum", "nagilum"))).getStatusCode());
        Assert.assertEquals("Unable to create index 'starfleet_library'", HttpStatus.SC_OK, executePutRequest("starfleet_library", null, new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("nagilum", "nagilum"))).getStatusCode());
        
        Thread.sleep(2000);
        waitForGreenClusterState(esNode1.client());
        
        Assert.assertEquals("Unable to close index 'starfleet_library'", HttpStatus.SC_OK, executePostRequest("starfleet_library/_close", null, new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("nagilum", "nagilum"))).getStatusCode());
        Assert.assertEquals("Unable to open index 'starfleet_library'", HttpStatus.SC_OK, (res = executePostRequest("starfleet_library/_open", null, new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("nagilum", "nagilum")))).getStatusCode());
        Assert.assertEquals("open index 'starfleet_library' not acknowledged", "{\"acknowledged\":true}", res.getBody());
        
        waitForGreenClusterState(esNode1.client());
        
        //Assert.assertEquals(HttpStatus.SC_OK, executePutRequest("public", null, new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("spock", "spock"))).getStatusCode());
        
        
    }
    
    @Test
    public void testHTTPProxy() throws Exception {

        final Settings settings = Settings.builder().put("searchguard.ssl.transport.enabled", true)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS, "node-0")
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.transport.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                .put("searchguard.ssl.transport.enforce_hostname_verification", false)
                .put("searchguard.ssl.transport.resolve_hostname", false)
                .putArray("searchguard.authcz.admin_dn", "CN=kirk,OU=client,O=client,l=tEst, C=De")
                .putArray("searchguard.authcz.impersonation_dn.CN=spock,OU=client,O=client,L=Test,C=DE", "worf")
                .build();
        
        startES(settings);

        Settings tcSettings = Settings.builder().put("cluster.name", clustername)
                .put(settings)
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("kirk-keystore.jks"))
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS,"kirk")
                .put("path.home", ".").build();

        try (TransportClient tc = TransportClient.builder().settings(tcSettings).addPlugin(SearchGuardSSLPlugin.class).addPlugin(SearchGuardPlugin.class).build()) {
            
            log.debug("Start transport client to init");
            
            tc.addTransportAddress(new InetSocketTransportAddress(new InetSocketAddress(nodeHost, nodePort)));
            Assert.assertEquals(3, tc.admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());

            tc.admin().indices().create(new CreateIndexRequest("searchguard")).actionGet();
            //tc.index(new IndexRequest("searchguard").type("dummy").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("", readYamlContent("sg_config_proxy.yml"))).actionGet();
            
            //Thread.sleep(5000);
            
            tc.index(new IndexRequest("searchguard").type("config").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("config", readYamlContent("sg_config_proxy.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("internalusers").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("internalusers", readYamlContent("sg_internal_users.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("roles").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("roles", readYamlContent("sg_roles.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("rolesmapping").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("rolesmapping", readYamlContent("sg_roles_mapping.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("actiongroups").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("actiongroups", readYamlContent("sg_action_groups.yml"))).actionGet();
            
            System.out.println("------- End INIT ---------");
            
            tc.index(new IndexRequest("vulcangov").type("kolinahr").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("vulcangov").type("secrets").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("vulcangov").type("planet").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            
            tc.index(new IndexRequest("starfleet").type("ships").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("starfleet").type("captains").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("starfleet").type("public").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            
            tc.index(new IndexRequest("starfleet_academy").type("students").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("starfleet_academy").type("alumni").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            
            tc.index(new IndexRequest("starfleet_library").type("public").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("starfleet_library").type("administration").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            
            tc.index(new IndexRequest("klingonempire").type("ships").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("klingonempire").type("praxis").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            
            tc.index(new IndexRequest("public").type("legends").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("public").type("hall_of_fame").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("public").type("hall_of_fame").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":2}")).actionGet();
            
            tc.admin().indices().aliases(new IndicesAliasesRequest().addAlias("sf", "starfleet","starfleet_academy","starfleet_library")).actionGet();
            tc.admin().indices().aliases(new IndicesAliasesRequest().addAlias("nonsf", "klingonempire","vulcangov")).actionGet();
            tc.admin().indices().aliases(new IndicesAliasesRequest().addAlias("unrestricted", "public")).actionGet();
            ConfigUpdateResponse cur = tc.execute(ConfigUpdateAction.INSTANCE, new ConfigUpdateRequest(new String[]{"config","roles","rolesmapping","internalusers","actiongroups"})).actionGet();
            Assert.assertEquals(3, cur.getNodes().size());
        }
        
       
        Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, executeGetRequest("").getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, executeGetRequest("", new BasicHeader("x-proxy-user", "scotty")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, executeGetRequest("", new BasicHeader("X-Proxy-User", "scotty")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, executeGetRequest("", new BasicHeader("x-proxy-user", "scotty"),new BasicHeader("x-proxy-roles", "starfleet,engineer")).getStatusCode());
        
    }
    
    /*@Test
    public void testHTTPLdap() throws Exception {

        Assume.assumeTrue(ReflectionHelper.canLoad("com.floragunn.dlic.auth.ldap.srv.EmbeddedLDAPServer"));
        
        final Settings settings = Settings.builder().put("searchguard.ssl.transport.enabled", true)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS, "node-0")
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.transport.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                .put("searchguard.ssl.transport.enforce_hostname_verification", false)
                .put("searchguard.ssl.transport.resolve_hostname", false)
                .putArray("searchguard.authcz.admin_dn", "CN=kirk,OU=client,O=client,l=tEst, C=De")
                .putArray("searchguard.authcz.impersonation_dn.CN=spock,OU=client,O=client,L=Test,C=DE", "worf")
                .build();
        
        startES(settings);
        
        com.floragunn.dlic.auth.ldap.srv.EmbeddedLDAPServer ldapServer = new com.floragunn.dlic.auth.ldap.srv.EmbeddedLDAPServer();
        ldapServer.start();
        ldapServer.applyLdif("ldap.ldif");

        Settings tcSettings = Settings.builder().put("cluster.name", clustername)
                .put(settings)
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("kirk-keystore.jks"))
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS,"kirk")
                .put("path.home", ".").build();

        try (TransportClient tc = TransportClient.builder().settings(tcSettings).addPlugin(SearchGuardSSLPlugin.class).build()) {
            
            log.debug("Start transport client to init");
            
            tc.addTransportAddress(new InetSocketTransportAddress(new InetSocketAddress(nodeHost, nodePort)));
            Assert.assertEquals(3, tc.admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());

            tc.admin().indices().create(new CreateIndexRequest("searchguard")).actionGet();
            tc.index(new IndexRequest("searchguard").type("dummy").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("", readYamlContent("sg_config_ldap.yml"))).actionGet();
            
            //Thread.sleep(5000);
            
            tc.index(new IndexRequest("searchguard").type("config").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("", readYamlContent("sg_config_ldap.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("internalusers").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("", readYamlContent("sg_internal_users.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("roles").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("", readYamlContent("sg_roles.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("rolesmapping").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("", readYamlContent("sg_roles_mapping.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("actiongroups").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("", readYamlContent("sg_action_groups.yml"))).actionGet();
            
            System.out.println("------- End INIT ---------");
            
            tc.index(new IndexRequest("vulcangov").type("kolinahr").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("vulcangov").type("secrets").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("vulcangov").type("planet").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            
            tc.index(new IndexRequest("starfleet").type("ships").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("starfleet").type("captains").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("starfleet").type("public").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            
            tc.index(new IndexRequest("starfleet_academy").type("students").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("starfleet_academy").type("alumni").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            
            tc.index(new IndexRequest("starfleet_library").type("public").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("starfleet_library").type("administration").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            
            tc.index(new IndexRequest("klingonempire").type("ships").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("klingonempire").type("praxis").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            
            tc.index(new IndexRequest("public").type("legends").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("public").type("hall_of_fame").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("public").type("hall_of_fame").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":2}")).actionGet();
            
            tc.admin().indices().aliases(new IndicesAliasesRequest().addAlias("sf", "starfleet","starfleet_academy","starfleet_library")).actionGet();
            tc.admin().indices().aliases(new IndicesAliasesRequest().addAlias("nonsf", "klingonempire","vulcangov")).actionGet();
            tc.admin().indices().aliases(new IndicesAliasesRequest().addAlias("unrestricted", "public")).actionGet();
            
        }
        
        try {
            //init is somewhat async
            Thread.sleep(2000);        
            Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, executeGetRequest("").getStatusCode());
            Assert.assertEquals(HttpStatus.SC_OK, executeGetRequest("", new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("spock", "spocksecret"))).getStatusCode());
            HttpResponse res =  executeGetRequest("_searchguard/authinfo?pretty", new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("spock", "spocksecret")));
            Assert.assertTrue(res.getBody().contains("nested1"));
            Assert.assertTrue(res.getBody().contains("nested2"));
            Assert.assertTrue(res.getBody().toLowerCase().contains("spock"));
        } finally {
            ldapServer.stop();
        }
    }*/
    
    @Test
    public void testTransportClient() throws Exception {

        final Settings settings = Settings.builder().put("searchguard.ssl.transport.enabled", true)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS, "node-0")
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.transport.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                .put("searchguard.ssl.transport.enforce_hostname_verification", false)
                .put("searchguard.ssl.transport.resolve_hostname", false)
                .build();
        
        final Settings esSettings = Settings.builder().put(settings)
                .putArray("searchguard.authcz.admin_dn", "CN=kirk,OU=client,O=client,l=tEst, C=De")
                
                /*
                searchguard.authcz.impersonation_dn:
                  "cn=technical_user1,ou=Test,ou=ou,dc=company,dc=com":
                    - '*'
                  "cn=webuser,ou=IT,ou=IT,dc=company,dc=com":
                    - 'kirk'
                    - 'user1'
                 
                 */
                
                .putArray("searchguard.authcz.impersonation_dn.CN=spock,OU=client,O=client,L=Test,C=DE", "worf", "nagilum")
                .build();
        
        System.out.println(settings.getAsMap());

        startES(esSettings);

        Settings tcSettings = Settings.builder().put("cluster.name", clustername)
                .put(settings)
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("kirk-keystore.jks"))
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS,"kirk")
                .put("path.home", ".").build();

        try (TransportClient tc = TransportClient.builder().settings(tcSettings).addPlugin(SearchGuardSSLPlugin.class).addPlugin(SearchGuardPlugin.class).build()) {
            
            log.debug("Start transport client to init");
            
            tc.addTransportAddress(new InetSocketTransportAddress(new InetSocketAddress(nodeHost, nodePort)));
            Assert.assertEquals(3, tc.admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());

            tc.admin().indices().create(new CreateIndexRequest("searchguard")).actionGet();
            //tc.index(new IndexRequest("searchguard").type("dummy").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("", readYamlContent("sg_config.yml"))).actionGet();
            
            System.out.println("------- Begin INIT ---------");
            
            //Thread.sleep(5000);
            
            tc.index(new IndexRequest("searchguard").type("config").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("config", readYamlContent("sg_config.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("internalusers").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("internalusers", readYamlContent("sg_internal_users.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("roles").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("roles", readYamlContent("sg_roles.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("rolesmapping").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("rolesmapping", readYamlContent("sg_roles_mapping.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("actiongroups").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("actiongroups", readYamlContent("sg_action_groups.yml"))).actionGet();
            
            tc.index(new IndexRequest("starfleet").type("ships").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            
            ConfigUpdateResponse cur = tc.execute(ConfigUpdateAction.INSTANCE, new ConfigUpdateRequest(new String[]{"config","roles","rolesmapping","internalusers","actiongroups"})).actionGet();
            Assert.assertEquals(3, cur.getNodes().size());
        
        }
        
        System.out.println("------- INIT complete ---------");
        
        tcSettings = Settings.builder().put("cluster.name", clustername)
                .put(settings)
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("spock-keystore.jks"))
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS,"spock")
                .put("path.home", ".").build();

        System.out.println("------- 0 ---------");
        
        try (TransportClient tc = TransportClient.builder().settings(tcSettings).addPlugin(SearchGuardSSLPlugin.class).build()) {
            
            log.debug("Start transport client to use");
            
            tc.addTransportAddress(new InetSocketTransportAddress(new InetSocketAddress(nodeHost, nodePort)));
            
            Thread.sleep(10000);
            
            Assert.assertEquals(3, tc.admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());
            
            System.out.println("------- 1 ---------");
            
            CreateIndexResponse cir = tc.admin().indices().create(new CreateIndexRequest("vulcan")).actionGet();
            Assert.assertTrue(cir.isAcknowledged());
            
            System.out.println("------- 2 ---------");
            
            IndexResponse ir = tc.index(new IndexRequest("vulcan").type("secrets").id("s1").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"secret\":true}")).actionGet();
            Assert.assertTrue(ir.isCreated());
            
            System.out.println("------- 3 ---------");
            
            GetResponse gr =tc.prepareGet("vulcan", "secrets", "s1").setRealtime(true).get();
            Assert.assertTrue(gr.isExists());
            
            System.out.println("------- 4 ---------");
            
            gr =tc.prepareGet("vulcan", "secrets", "s1").setRealtime(false).get();
            Assert.assertTrue(gr.isExists());
            
            System.out.println("------- 5 ---------");
            
            SearchResponse actionGet = tc.search(new SearchRequest("vulcan").types("secrets")).actionGet();
            Assert.assertEquals(1, actionGet.getHits().getHits().length);
            System.out.println("------- 6 ---------");
            
            gr =tc.prepareGet("searchguard", "config", "0").setRealtime(false).get();
            Assert.assertFalse(gr.isExists());
            
            System.out.println("------- 7 ---------");
            
            gr =tc.prepareGet("searchguard", "config", "0").setRealtime(true).get();
            Assert.assertFalse(gr.isExists());
            
            System.out.println("------- 8 ---------");
            
            actionGet = tc.search(new SearchRequest("searchguard")).actionGet();
            Assert.assertEquals(0, actionGet.getHits().getHits().length);
            
            System.out.println("------- 9 ---------");
            
            try {
                tc.index(new IndexRequest("searchguard").type("config").id("0").source("config", readYamlContent("sg_config.yml"))).actionGet();
                Assert.fail();
            } catch (Exception e) {
                // TODO Auto-generated catch block
                System.out.println(e.getMessage());
            }
            
            System.out.println("------- 10 ---------");
            
            //impersonation
            try {
                
                StoredContext ctx = tc.threadPool().getThreadContext().newStoredContext();
                try {
                    tc.threadPool().getThreadContext().putHeader("sg_impersonate_as", "worf");
                    gr = tc.prepareGet("vulcan", "secrets", "s1").get();
                } finally {
                    ctx.close();
                }
                Assert.fail();
            } catch (ElasticsearchSecurityException e) {
               Assert.assertEquals("no permissions for indices:data/read/get", e.getMessage());
            }
            
            System.out.println("------- 11 ---------");

            // impersonation
            try {
                StoredContext ctx = tc.threadPool().getThreadContext().newStoredContext();
                try {
                    tc.threadPool().getThreadContext().putHeader("sg_impersonate_as", "gkar");
                    gr = tc.prepareGet("vulcan", "secrets", "s1").get();
                    Assert.fail();
                } finally {
                    ctx.close();
                }

            } catch (ElasticsearchSecurityException e) {
                Assert.assertEquals("'CN=spock,OU=client,O=client,L=Test,C=DE' is not allowed to impersonate as 'gkar'", e.getMessage());
            }

            System.out.println("------- 12 ---------");

            StoredContext ctx = tc.threadPool().getThreadContext().newStoredContext();
            try {
                tc.threadPool().getThreadContext().putHeader("sg_impersonate_as", "nagilum");
                gr = tc.prepareGet("searchguard", "config", "0").setRealtime(Boolean.TRUE).get();
                Assert.assertFalse(gr.isExists());
                Assert.assertTrue(gr.isSourceEmpty());
            } finally {
                ctx.close();
            }

            System.out.println("------- 13 ---------");
            ctx = tc.threadPool().getThreadContext().newStoredContext();
            try {
                tc.threadPool().getThreadContext().putHeader("sg_impersonate_as", "nagilum");
                gr = tc.prepareGet("searchguard", "config", "0").setRealtime(Boolean.FALSE).get();
                Assert.assertFalse(gr.isExists());
                Assert.assertTrue(gr.isSourceEmpty());
            } finally {
                ctx.close();
            }
            
            
            String scrollId = null;
            ctx = tc.threadPool().getThreadContext().newStoredContext();
            try {
                tc.threadPool().getThreadContext().putHeader("sg_impersonate_as", "nagilum");
                SearchResponse searchRes = tc.prepareSearch("starfleet").setTypes("ships").setScroll(TimeValue.timeValueMinutes(5)).get();
                scrollId = searchRes.getScrollId();
            } finally {
                ctx.close();
            }

            //TODO fails (but this could be ok?)
            ctx = tc.threadPool().getThreadContext().newStoredContext();
            try {
                tc.threadPool().getThreadContext().putHeader("sg_impersonate_as", "worf");
                SearchResponse scrollRes = tc.prepareSearchScroll(scrollId).get();
            } finally {
                ctx.close();
            }
            
            
            System.out.println("------- TRC end ---------");
        }
        
        System.out.println("------- CTC end ---------");
    }
    
    
    
    
    
    
    
    
    public void testHttps() throws Exception {

        enableHTTPClientSSL = true;
        trustHTTPServerCertificate = true;
        sendHTTPClientCertificate = true;

        final Settings settings = Settings.builder().put("searchguard.ssl.transport.enabled", false)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_KEYSTORE_ALIAS, "node-0").put("searchguard.ssl.http.enabled", true)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put("searchguard.ssl.http.enforce_clientauth", true)
                .put("searchguard.ssl.http.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.http.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks")).build();

        startES(settings);

        System.out.println(executeSimpleRequest("_searchguard/sslinfo?pretty"));
        Assert.assertTrue(executeSimpleRequest("_searchguard/sslinfo?pretty").contains("TLS"));
        Assert.assertTrue(executeSimpleRequest("_nodes/settings?pretty").contains(clustername));
        Assert.assertFalse(executeSimpleRequest("_nodes/settings?pretty").contains("\"searchguard\""));
    }
    
    @Test
    public void testSpecialUsernames() throws Exception {

        final Settings settings = Settings.builder().put("searchguard.ssl.transport.enabled", true)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS, "node-0")
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.transport.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                .put("searchguard.ssl.transport.enforce_hostname_verification", false)
                .put("searchguard.ssl.transport.resolve_hostname", false)
                .putArray("searchguard.authcz.admin_dn", "CN=kirk,OU=client,O=client,l=tEst, C=De")
                
                /*
                searchguard.authcz.impersonation_dn:
                  "cn=technical_user1,ou=Test,ou=ou,dc=company,dc=com":
                    - '*'
                  "cn=webuser,ou=IT,ou=IT,dc=company,dc=com":
                    - 'kirk'
                    - 'user1'
                 
                 */
                
                .putArray("searchguard.authcz.impersonation_dn.CN=spock,OU=client,O=client,L=Test,C=DE", "worf")
                .build();
        
        startES(settings);

        Settings tcSettings = Settings.builder().put("cluster.name", clustername)
                .put(settings)
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("kirk-keystore.jks"))
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS,"kirk")
                .put("path.home", ".").build();

        try (TransportClient tc = TransportClient.builder().settings(tcSettings).addPlugin(SearchGuardSSLPlugin.class).addPlugin(SearchGuardPlugin.class).build()) {
            
            log.debug("Start transport client to init");
            
            tc.addTransportAddress(new InetSocketTransportAddress(new InetSocketAddress(nodeHost, nodePort)));
            Assert.assertEquals(3, tc.admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());

            tc.admin().indices().create(new CreateIndexRequest("searchguard")).actionGet();
            //tc.index(new IndexRequest("searchguard").type("dummy").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("", readYamlContent("sg_config.yml"))).actionGet();
            
            //Thread.sleep(5000);
            
            tc.index(new IndexRequest("searchguard").type("config").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("config", readYamlContent("sg_config.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("internalusers").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("internalusers", readYamlContent("sg_internal_users.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("roles").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("roles", readYamlContent("sg_roles.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("rolesmapping").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("rolesmapping", readYamlContent("sg_roles_mapping.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("actiongroups").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("actiongroups", readYamlContent("sg_action_groups.yml"))).actionGet();
            
            System.out.println("------- End INIT ---------");
            
            tc.index(new IndexRequest("vulcangov").type("kolinahr").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("vulcangov").type("secrets").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("vulcangov").type("planet").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            
            tc.index(new IndexRequest("starfleet").type("ships").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("starfleet").type("captains").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("starfleet").type("public").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            
            tc.index(new IndexRequest("starfleet_academy").type("students").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("starfleet_academy").type("alumni").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            
            tc.index(new IndexRequest("starfleet_library").type("public").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("starfleet_library").type("administration").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            
            tc.index(new IndexRequest("klingonempire").type("ships").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("klingonempire").type("praxis").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            
            tc.index(new IndexRequest("public").type("legends").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("public").type("hall_of_fame").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("public").type("hall_of_fame").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":2}")).actionGet();
            
            tc.admin().indices().aliases(new IndicesAliasesRequest().addAlias("sf", "starfleet","starfleet_academy","starfleet_library")).actionGet();
            tc.admin().indices().aliases(new IndicesAliasesRequest().addAlias("nonsf", "klingonempire","vulcangov")).actionGet();
            tc.admin().indices().aliases(new IndicesAliasesRequest().addAlias("unrestricted", "public")).actionGet();
            ConfigUpdateResponse cur = tc.execute(ConfigUpdateAction.INSTANCE, new ConfigUpdateRequest(new String[]{"config","roles","rolesmapping","internalusers","actiongroups"})).actionGet();
            Assert.assertEquals(3, cur.getNodes().size());
        }
       
        Assert.assertEquals(HttpStatus.SC_OK, executeGetRequest("", new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("bug.99", "nagilum"))).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, executeGetRequest("", new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("a", "b"))).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, executeGetRequest("", new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("\"'+-,;_?*@<>!$%&/()=#", "nagilum"))).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, executeGetRequest("", new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("§ÄÖÜäöüß", "nagilum"))).getStatusCode());

    }

       @Test
        public void testDlsFls() throws Exception {

            Assume.assumeTrue(ReflectionHelper.canLoad("com.floragunn.searchguard.configuration.SearchGuardFlsDlsIndexSearcherWrapper"));
        
            final Settings settings = Settings.builder().put("searchguard.ssl.transport.enabled", true)
                    .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                    .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                    .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS, "node-0")
                    .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                    .put("searchguard.ssl.transport.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                    .put("searchguard.ssl.transport.enforce_hostname_verification", false)
                    .put("searchguard.ssl.transport.resolve_hostname", false)
                    .putArray("searchguard.authcz.admin_dn", "CN=kirk,OU=client,O=client,l=tEst, C=De")
                    
                    /*
                    searchguard.authcz.impersonation_dn:
                      "cn=technical_user1,ou=Test,ou=ou,dc=company,dc=com":
                        - '*'
                      "cn=webuser,ou=IT,ou=IT,dc=company,dc=com":
                        - 'kirk'
                        - 'user1'
                     
                     */
                    
                    .putArray("searchguard.authcz.impersonation_dn.CN=spock,OU=client,O=client,L=Test,C=DE", "worf")
                    .build();
            
            startES(settings);
    
            Settings tcSettings = Settings.builder().put("cluster.name", clustername)
                    .put(settings)
                    .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("kirk-keystore.jks"))
                    .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS,"kirk")
                    .put("path.home", ".").build();
    
            try (TransportClient tc = TransportClient.builder().settings(tcSettings).addPlugin(SearchGuardSSLPlugin.class).addPlugin(SearchGuardPlugin.class).build()) {
                
                log.debug("Start transport client to init");
                
                tc.addTransportAddress(new InetSocketTransportAddress(new InetSocketAddress(nodeHost, nodePort)));
                Assert.assertEquals(3, tc.admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());
    
                tc.admin().indices().create(new CreateIndexRequest("searchguard")).actionGet();
                //tc.index(new IndexRequest("searchguard").type("dummy").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("", readYamlContent("sg_config.yml"))).actionGet();
                
                //Thread.sleep(5000);
                
                tc.index(new IndexRequest("searchguard").type("config").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("config", readYamlContent("sg_config.yml"))).actionGet();
                tc.index(new IndexRequest("searchguard").type("internalusers").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("internalusers", readYamlContent("sg_internal_users.yml"))).actionGet();
                tc.index(new IndexRequest("searchguard").type("roles").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("roles", readYamlContent("sg_roles.yml"))).actionGet();
                tc.index(new IndexRequest("searchguard").type("rolesmapping").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("rolesmapping", readYamlContent("sg_roles_mapping.yml"))).actionGet();
                tc.index(new IndexRequest("searchguard").type("actiongroups").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("actiongroups", readYamlContent("sg_action_groups.yml"))).actionGet();
                
                System.out.println("------- End INIT ---------");
                
                tc.index(new IndexRequest("vulcangov").type("kolinahr").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
                tc.index(new IndexRequest("vulcangov").type("secrets").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
                tc.index(new IndexRequest("vulcangov").type("planet").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
                
                tc.index(new IndexRequest("starfleet").type("ships").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
                tc.index(new IndexRequest("starfleet").type("captains").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
                tc.index(new IndexRequest("starfleet").type("public").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
                
                tc.index(new IndexRequest("starfleet_academy").type("students").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
                tc.index(new IndexRequest("starfleet_academy").type("alumni").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
                
                tc.index(new IndexRequest("starfleet_library").type("public").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
                tc.index(new IndexRequest("starfleet_library").type("administration").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
                
                tc.index(new IndexRequest("klingonempire").type("ships").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
                tc.index(new IndexRequest("klingonempire").type("praxis").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
                
                tc.index(new IndexRequest("public").type("legends").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
                tc.index(new IndexRequest("public").type("hall_of_fame").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
                tc.index(new IndexRequest("public").type("hall_of_fame").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":2}")).actionGet();
                
                tc.admin().indices().aliases(new IndicesAliasesRequest().addAlias("sf", "starfleet","starfleet_academy","starfleet_library")).actionGet();
                tc.admin().indices().aliases(new IndicesAliasesRequest().addAlias("nonsf", "klingonempire","vulcangov")).actionGet();
                tc.admin().indices().aliases(new IndicesAliasesRequest().addAlias("unrestricted", "public")).actionGet();
                ConfigUpdateResponse cur = tc.execute(ConfigUpdateAction.INSTANCE, new ConfigUpdateRequest(new String[]{"config","roles","rolesmapping","internalusers","actiongroups"})).actionGet();
                Assert.assertEquals(3, cur.getNodes().size());
            }
       
            Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, executeGetRequest("").getStatusCode());
            HttpResponse res;
            Assert.assertEquals(HttpStatus.SC_OK, (res = executeGetRequest("/_search?pretty", new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("sarek", "sarek")))).getStatusCode());
            Assert.assertTrue(res.getBody().contains("\"total\" : 1,"));
            Assert.assertTrue(res.getBody().contains("\"_source\" : { }"));
            
        }

    @Test
        public void testHTTPAnon() throws Exception {
    
            final Settings settings = Settings.builder().put("searchguard.ssl.transport.enabled", true)
                    .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                    .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                    .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS, "node-0")
                    .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                    .put("searchguard.ssl.transport.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                    .put("searchguard.ssl.transport.enforce_hostname_verification", false)
                    .put("searchguard.ssl.transport.resolve_hostname", false)
                    .putArray("searchguard.authcz.admin_dn", "CN=kirk,OU=client,O=client,l=tEst, C=De")
                    
                    /*
                    searchguard.authcz.impersonation_dn:
                      "cn=technical_user1,ou=Test,ou=ou,dc=company,dc=com":
                        - '*'
                      "cn=webuser,ou=IT,ou=IT,dc=company,dc=com":
                        - 'kirk'
                        - 'user1'
                     
                     */
                    
                    .putArray("searchguard.authcz.impersonation_dn.CN=spock,OU=client,O=client,L=Test,C=DE", "worf")
                    .build();
            
            startES(settings);
    
            Settings tcSettings = Settings.builder().put("cluster.name", clustername)
                    .put(settings)
                    .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("kirk-keystore.jks"))
                    .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS,"kirk")
                    .put("path.home", ".").build();
    
            try (TransportClient tc = TransportClient.builder().settings(tcSettings).addPlugin(SearchGuardSSLPlugin.class).addPlugin(SearchGuardPlugin.class).build()) {
                
                log.debug("Start transport client to init");
                
                tc.addTransportAddress(new InetSocketTransportAddress(new InetSocketAddress(nodeHost, nodePort)));
                Assert.assertEquals(3, tc.admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());
    
                tc.admin().indices().create(new CreateIndexRequest("searchguard")).actionGet();
                //tc.index(new IndexRequest("searchguard").type("dummy").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("", readYamlContent("sg_config.yml"))).actionGet();
                
                //Thread.sleep(5000);
                
                tc.index(new IndexRequest("searchguard").type("config").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("config", readYamlContent("sg_config_anon.yml"))).actionGet();
                tc.index(new IndexRequest("searchguard").type("internalusers").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("internalusers", readYamlContent("sg_internal_users.yml"))).actionGet();
                tc.index(new IndexRequest("searchguard").type("roles").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("roles", readYamlContent("sg_roles.yml"))).actionGet();
                tc.index(new IndexRequest("searchguard").type("rolesmapping").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("rolesmapping", readYamlContent("sg_roles_mapping.yml"))).actionGet();
                tc.index(new IndexRequest("searchguard").type("actiongroups").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("actiongroups", readYamlContent("sg_action_groups.yml"))).actionGet();
                
                System.out.println("------- End INIT ---------");
                
                tc.index(new IndexRequest("vulcangov").type("kolinahr").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
                tc.index(new IndexRequest("vulcangov").type("secrets").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
                tc.index(new IndexRequest("vulcangov").type("planet").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
                
                tc.index(new IndexRequest("starfleet").type("ships").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
                tc.index(new IndexRequest("starfleet").type("captains").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
                tc.index(new IndexRequest("starfleet").type("public").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
                
                tc.index(new IndexRequest("starfleet_academy").type("students").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
                tc.index(new IndexRequest("starfleet_academy").type("alumni").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
                
                tc.index(new IndexRequest("starfleet_library").type("public").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
                tc.index(new IndexRequest("starfleet_library").type("administration").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
                
                tc.index(new IndexRequest("klingonempire").type("ships").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
                tc.index(new IndexRequest("klingonempire").type("praxis").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
                
                tc.index(new IndexRequest("public").type("legends").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
                tc.index(new IndexRequest("public").type("hall_of_fame").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
                tc.index(new IndexRequest("public").type("hall_of_fame").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":2}")).actionGet();
                
                tc.admin().indices().aliases(new IndicesAliasesRequest().addAlias("sf", "starfleet","starfleet_academy","starfleet_library")).actionGet();
                tc.admin().indices().aliases(new IndicesAliasesRequest().addAlias("nonsf", "klingonempire","vulcangov")).actionGet();
                tc.admin().indices().aliases(new IndicesAliasesRequest().addAlias("unrestricted", "public")).actionGet();
                ConfigUpdateResponse cur = tc.execute(ConfigUpdateAction.INSTANCE, new ConfigUpdateRequest(new String[]{"config","roles","rolesmapping","internalusers","actiongroups"})).actionGet();
                Assert.assertEquals(3, cur.getNodes().size());
            }
            
       
            Assert.assertEquals(HttpStatus.SC_OK, executeGetRequest("").getStatusCode());
            Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, executeGetRequest("", new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("worf", "wrong"))).getStatusCode());
            Assert.assertEquals(HttpStatus.SC_OK, executeGetRequest("", new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("nagilum", "nagilum"))).getStatusCode());
   
            HttpResponse resc = executeGetRequest("_searchguard/authinfo");
            System.out.println(resc.getBody());
            Assert.assertTrue(resc.getBody().contains("sg_anonymous"));
            Assert.assertEquals(HttpStatus.SC_OK, resc.getStatusCode());
            
            resc = executeGetRequest("_searchguard/authinfo", new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("nagilum", "nagilum")));
            System.out.println(resc.getBody());
            Assert.assertTrue(resc.getBody().contains("nagilum"));
            Assert.assertFalse(resc.getBody().contains("sg_anonymous"));
            Assert.assertEquals(HttpStatus.SC_OK, resc.getStatusCode());
            
            try (TransportClient tc = TransportClient.builder().settings(tcSettings).addPlugin(SearchGuardSSLPlugin.class).addPlugin(SearchGuardPlugin.class).build()) {
                
                log.debug("Start transport client to init");
                
                tc.addTransportAddress(new InetSocketTransportAddress(new InetSocketAddress(nodeHost, nodePort)));
                Assert.assertEquals(3, tc.admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());
    
                tc.index(new IndexRequest("searchguard").type("config").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("config", readYamlContent("sg_config.yml"))).actionGet();
                tc.index(new IndexRequest("searchguard").type("internalusers").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("internalusers", readYamlContent("sg_internal_users.yml"))).actionGet();
                ConfigUpdateResponse cur = tc.execute(ConfigUpdateAction.INSTANCE, new ConfigUpdateRequest(new String[]{"config","roles","rolesmapping","internalusers","actiongroups"})).actionGet();
                Assert.assertEquals(3, cur.getNodes().size());
             }

            
            Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, executeGetRequest("").getStatusCode());
            Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, executeGetRequest("_searchguard/authinfo").getStatusCode());
            Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, executeGetRequest("", new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("worf", "wrong"))).getStatusCode());
            Assert.assertEquals(HttpStatus.SC_OK, executeGetRequest("", new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("nagilum", "nagilum"))).getStatusCode());
    }

    /*@Test
    public void testHTTPLdap() throws Exception {
    
        Assume.assumeTrue(ReflectionHelper.canLoad("com.floragunn.dlic.auth.ldap.srv.EmbeddedLDAPServer"));
        
        final Settings settings = Settings.builder().put("searchguard.ssl.transport.enabled", true)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS, "node-0")
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.transport.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                .put("searchguard.ssl.transport.enforce_hostname_verification", false)
                .put("searchguard.ssl.transport.resolve_hostname", false)
                .putArray("searchguard.authcz.admin_dn", "CN=kirk,OU=client,O=client,l=tEst, C=De")
                .putArray("searchguard.authcz.impersonation_dn.CN=spock,OU=client,O=client,L=Test,C=DE", "worf")
                .build();
        
        startES(settings);
        
        com.floragunn.dlic.auth.ldap.srv.EmbeddedLDAPServer ldapServer = new com.floragunn.dlic.auth.ldap.srv.EmbeddedLDAPServer();
        ldapServer.start();
        ldapServer.applyLdif("ldap.ldif");
    
        Settings tcSettings = Settings.builder().put("cluster.name", clustername)
                .put(settings)
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("kirk-keystore.jks"))
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS,"kirk")
                .put("path.home", ".").build();
    
        try (TransportClient tc = TransportClient.builder().settings(tcSettings).addPlugin(SearchGuardSSLPlugin.class).build()) {
            
            log.debug("Start transport client to init");
            
            tc.addTransportAddress(new InetSocketTransportAddress(new InetSocketAddress(nodeHost, nodePort)));
            Assert.assertEquals(3, tc.admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());
    
            tc.admin().indices().create(new CreateIndexRequest("searchguard")).actionGet();
            tc.index(new IndexRequest("searchguard").type("dummy").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("", readYamlContent("sg_config_ldap.yml"))).actionGet();
            
            //Thread.sleep(5000);
            
            tc.index(new IndexRequest("searchguard").type("config").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("", readYamlContent("sg_config_ldap.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("internalusers").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("", readYamlContent("sg_internal_users.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("roles").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("", readYamlContent("sg_roles.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("rolesmapping").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("", readYamlContent("sg_roles_mapping.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("actiongroups").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("", readYamlContent("sg_action_groups.yml"))).actionGet();
            
            System.out.println("------- End INIT ---------");
            
            tc.index(new IndexRequest("vulcangov").type("kolinahr").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("vulcangov").type("secrets").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("vulcangov").type("planet").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            
            tc.index(new IndexRequest("starfleet").type("ships").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("starfleet").type("captains").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("starfleet").type("public").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            
            tc.index(new IndexRequest("starfleet_academy").type("students").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("starfleet_academy").type("alumni").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            
            tc.index(new IndexRequest("starfleet_library").type("public").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("starfleet_library").type("administration").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            
            tc.index(new IndexRequest("klingonempire").type("ships").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("klingonempire").type("praxis").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            
            tc.index(new IndexRequest("public").type("legends").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("public").type("hall_of_fame").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            tc.index(new IndexRequest("public").type("hall_of_fame").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":2}")).actionGet();
            
            tc.admin().indices().aliases(new IndicesAliasesRequest().addAlias("sf", "starfleet","starfleet_academy","starfleet_library")).actionGet();
            tc.admin().indices().aliases(new IndicesAliasesRequest().addAlias("nonsf", "klingonempire","vulcangov")).actionGet();
            tc.admin().indices().aliases(new IndicesAliasesRequest().addAlias("unrestricted", "public")).actionGet();
            
        }
        
        try {
            //init is somewhat async
            Thread.sleep(2000);        
            Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, executeGetRequest("").getStatusCode());
            Assert.assertEquals(HttpStatus.SC_OK, executeGetRequest("", new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("spock", "spocksecret"))).getStatusCode());
            HttpResponse res =  executeGetRequest("_searchguard/authinfo?pretty", new BasicHeader("Authorization", "Basic "+Base64Helper.encodeBasicHeader("spock", "spocksecret")));
            Assert.assertTrue(res.getBody().contains("nested1"));
            Assert.assertTrue(res.getBody().contains("nested2"));
            Assert.assertTrue(res.getBody().toLowerCase().contains("spock"));
        } finally {
            ldapServer.stop();
        }
    }*/
    
    @Test
    public void testTransportClientImpersonation() throws Exception {
    
        final Settings settings = Settings.builder().put("searchguard.ssl.transport.enabled", true)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS, "node-0")
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.transport.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                .put("searchguard.ssl.transport.enforce_hostname_verification", false)
                .put("searchguard.ssl.transport.resolve_hostname", false).build();
        
        final Settings esSettings = Settings.builder().put(settings)
                .putArray("searchguard.authcz.admin_dn", "CN=kirk,OU=client,O=client,l=tEst, C=De")
                
                /*
                searchguard.authcz.impersonation_dn:
                  "cn=technical_user1,ou=Test,ou=ou,dc=company,dc=com":
                    - '*'
                  "cn=webuser,ou=IT,ou=IT,dc=company,dc=com":
                    - 'kirk'
                    - 'user1'
                 
                 */
                
                .putArray("searchguard.authcz.impersonation_dn.CN=spock,OU=client,O=client,L=Test,C=DE", "worf", "nagilum")
                .build();
        
        System.out.println(settings.getAsMap());
    
        startES(esSettings);
    
        Settings tcSettings = Settings.builder().put("cluster.name", clustername)
                .put(settings)
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("kirk-keystore.jks"))
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS,"kirk")
                .put("path.home", ".").build();
    
        try (TransportClient tc = TransportClient.builder().settings(tcSettings).addPlugin(SearchGuardSSLPlugin.class).addPlugin(SearchGuardPlugin.class).build()) {
            
            log.debug("Start transport client to init");
            
            tc.addTransportAddress(new InetSocketTransportAddress(new InetSocketAddress(nodeHost, nodePort)));
            Assert.assertEquals(3, tc.admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());
    
            tc.admin().indices().create(new CreateIndexRequest("searchguard")).actionGet();
            //tc.index(new IndexRequest("searchguard").type("dummy").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("", readYamlContent("sg_config.yml"))).actionGet();
            
            System.out.println("------- Begin INIT ---------");
            
            //Thread.sleep(5000);
            
            tc.index(new IndexRequest("searchguard").type("config").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("config", readYamlContent("sg_config.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("internalusers").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("internalusers", readYamlContent("sg_internal_users.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("roles").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("roles", readYamlContent("sg_roles.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("rolesmapping").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("rolesmapping", readYamlContent("sg_roles_mapping.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("actiongroups").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("actiongroups", readYamlContent("sg_action_groups.yml"))).actionGet();
            
            tc.index(new IndexRequest("starfleet").type("ships").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            
            ConfigUpdateResponse cur = tc.execute(ConfigUpdateAction.INSTANCE, new ConfigUpdateRequest(new String[]{"config","roles","rolesmapping","internalusers","actiongroups"})).actionGet();
            Assert.assertEquals(3, cur.getNodes().size());
        
        }
        
        System.out.println("------- INIT complete ---------");
        
        tcSettings = Settings.builder().put("cluster.name", clustername)
                .put(settings)
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("spock-keystore.jks"))
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS,"spock")
                .put("path.home", ".")
                .put("request.headers.sg_impersonate_as", "worf")
                .build();
    
        System.out.println("------- 0 ---------");
        
        try (TransportClient tc = TransportClient.builder().settings(tcSettings).addPlugin(SearchGuardSSLPlugin.class).build()) {
            
            log.debug("Start transport client to use");
            
            tc.addTransportAddress(new InetSocketTransportAddress(new InetSocketAddress(nodeHost, nodePort)));
            NodesInfoRequest nir = new NodesInfoRequest();
            
            Assert.assertEquals(3, tc.admin().cluster().nodesInfo(nir).actionGet().getNodes().size());
            
            
            System.out.println("------- TRC end ---------");
        }
        
        System.out.println("------- CTC end ---------");
    }
    
    @Test
    public void testTransportClientImpersonationWildcard() throws Exception {
    
        final Settings settings = Settings.builder().put("searchguard.ssl.transport.enabled", true)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_ENABLE_OPENSSL_IF_AVAILABLE, allowOpenSSL)
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS, "node-0")
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.transport.truststore_filepath", getAbsoluteFilePathFromClassPath("truststore.jks"))
                .put("searchguard.ssl.transport.enforce_hostname_verification", false)
                .put("searchguard.ssl.transport.resolve_hostname", false).build();
        
        final Settings esSettings = Settings.builder().put(settings)
                .putArray("searchguard.authcz.admin_dn", "CN=kirk,OU=client,O=client,l=tEst, C=De")
                
                /*
                searchguard.authcz.impersonation_dn:
                  "cn=technical_user1,ou=Test,ou=ou,dc=company,dc=com":
                    - '*'
                  "cn=webuser,ou=IT,ou=IT,dc=company,dc=com":
                    - 'kirk'
                    - 'user1'
                 
                 */
                
                .putArray("searchguard.authcz.impersonation_dn.CN=spock,OU=client,O=client,L=Test,C=DE", "*")
                .build();
        
        System.out.println(settings.getAsMap());
    
        startES(esSettings);
    
        Settings tcSettings = Settings.builder().put("cluster.name", clustername)
                .put(settings)
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("kirk-keystore.jks"))
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS,"kirk")
                .put("path.home", ".").build();
    
        try (TransportClient tc = TransportClient.builder().settings(tcSettings).addPlugin(SearchGuardSSLPlugin.class).addPlugin(SearchGuardPlugin.class).build()) {
            
            log.debug("Start transport client to init");
            
            tc.addTransportAddress(new InetSocketTransportAddress(new InetSocketAddress(nodeHost, nodePort)));
            Assert.assertEquals(3, tc.admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());
    
            tc.admin().indices().create(new CreateIndexRequest("searchguard")).actionGet();
            //tc.index(new IndexRequest("searchguard").type("dummy").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("", readYamlContent("sg_config.yml"))).actionGet();
            
            System.out.println("------- Begin INIT ---------");
            
            //Thread.sleep(5000);
            
            tc.index(new IndexRequest("searchguard").type("config").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("config", readYamlContent("sg_config.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("internalusers").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("internalusers", readYamlContent("sg_internal_users.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("roles").id("0").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("roles", readYamlContent("sg_roles.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("rolesmapping").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("rolesmapping", readYamlContent("sg_roles_mapping.yml"))).actionGet();
            tc.index(new IndexRequest("searchguard").type("actiongroups").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0").source("actiongroups", readYamlContent("sg_action_groups.yml"))).actionGet();
            
            tc.index(new IndexRequest("starfleet").type("ships").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}")).actionGet();
            
            ConfigUpdateResponse cur = tc.execute(ConfigUpdateAction.INSTANCE, new ConfigUpdateRequest(new String[]{"config","roles","rolesmapping","internalusers","actiongroups"})).actionGet();
            Assert.assertEquals(3, cur.getNodes().size());
        
        }
        
        System.out.println("------- INIT complete ---------");
        
        tcSettings = Settings.builder().put("cluster.name", clustername)
                .put(settings)
                .put("searchguard.ssl.transport.keystore_filepath", getAbsoluteFilePathFromClassPath("spock-keystore.jks"))
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS,"spock")
                .put("path.home", ".")
                .put("request.headers.sg_impersonate_as", "worf")
                .build();
    
        System.out.println("------- 0 ---------");
        
        try (TransportClient tc = TransportClient.builder().settings(tcSettings).addPlugin(SearchGuardSSLPlugin.class).build()) {
            
            log.debug("Start transport client to use");
            
            tc.addTransportAddress(new InetSocketTransportAddress(new InetSocketAddress(nodeHost, nodePort)));
            NodesInfoRequest nir = new NodesInfoRequest();
            //nir.putHeader("_sg_request.headers.sg_impersonate_as", "worf1111");
            
            Assert.assertEquals(3, tc.admin().cluster().nodesInfo(nir).actionGet().getNodes().size());
            
            System.out.println("------- TRC end ---------");
        }
        
        System.out.println("------- CTC end ---------");
    }
}
