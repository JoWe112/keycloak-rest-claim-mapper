package com.github.jowe112.keycloak.mapper;

import org.jboss.logging.Logger;
import org.keycloak.dom.saml.v2.assertion.AttributeStatementType;
import org.keycloak.dom.saml.v2.assertion.AttributeType;
import org.keycloak.models.*;
import org.keycloak.protocol.saml.mappers.AbstractSAMLProtocolMapper;
import org.keycloak.protocol.saml.mappers.SAMLAttributeStatementMapper;
import org.keycloak.protocol.saml.mappers.AttributeStatementHelper;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.*;

public class SamlRestClaimMapper extends AbstractSAMLProtocolMapper implements SAMLAttributeStatementMapper {

    private static final Logger LOG = Logger.getLogger(SamlRestClaimMapper.class);

    public static final String PROVIDER_ID = "saml-rest-claim-mapper";
    public static final String DISPLAY_TYPE = "SAML REST Attribute Enrichment";
    public static final String DISPLAY_CATEGORY = "Attribute mapper";

    private static final List<ProviderConfigProperty> CONFIG_PROPERTIES;

    static {
        List<ProviderConfigProperty> props = new ArrayList<>();

        for (ProviderConfigProperty p : RestMapperConfig.COMMON_PROPERTIES) {
            props.add(p);
            
            // Inject SAML NameFormat right after the endpoint's mapping property
            for (int n = 1; n <= ConfigParser.MAX_ENDPOINTS; n++) {
                if (("endpoint." + n + ".mapping").equals(p.getName())) {
                    ProviderConfigProperty nameFormat = new ProviderConfigProperty();
                    nameFormat.setName("endpoint." + n + ".saml.nameformat");
                    nameFormat.setLabel("Endpoint " + n + ": SAML NameFormat");
                    nameFormat.setHelpText("SAML NameFormat for the attributes produced by Endpoint " + n + ".");
                    nameFormat.setType(ProviderConfigProperty.LIST_TYPE);
                    nameFormat.setOptions(Arrays.asList(AttributeStatementHelper.BASIC, AttributeStatementHelper.URI_REFERENCE, AttributeStatementHelper.UNSPECIFIED));
                    nameFormat.setDefaultValue(AttributeStatementHelper.BASIC);
                    props.add(nameFormat);
                    break;
                }
            }
        }

        CONFIG_PROPERTIES = Collections.unmodifiableList(props);
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return DISPLAY_TYPE;
    }

    @Override
    public String getDisplayCategory() {
        return DISPLAY_CATEGORY;
    }

    @Override
    public String getHelpText() {
        return "Enriches SAML tokens with attributes fetched from one or more external REST APIs. "
             + "Works for any federation (LDAP, AD, etc.) and supports both persistent "
             + "(imported) and transient (non-imported) users.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return CONFIG_PROPERTIES;
    }

    @Override
    public void transformAttributeStatement(AttributeStatementType attributeStatement, ProtocolMapperModel mappingModel,
                                            KeycloakSession session, UserSessionModel userSession,
                                            AuthenticatedClientSessionModel clientSession) {
        try {
            Map<String, String> config = mappingModel.getConfig();
            List<EndpointConfig> endpoints = ConfigParser.parse(config);

            if (endpoints.isEmpty()) {
                LOG.debugf("No endpoints configured for mapper '%s'", mappingModel.getName());
                return;
            }

            UserModel user = userSession.getUser();
            Map<String, String> userCtx = RestMapperConfig.buildUserContext(user, userSession);

            long ttl = RestMapperConfig.parseLongOrDefault(config.get(RestMapperConfig.CFG_CACHE_TTL), 300L);

            Map<String, Object> claims;
            if (RestMapperConfig.isPersistentUser(user)) {
                claims = PersistentUserHandler.fetchAndCache(user, mappingModel.getId(), endpoints, userCtx, ttl);
            } else {
                claims = TransientUserHandler.fetchLive(endpoints, userCtx);
            }

            Map<String, String> claimNameFormatMap = new HashMap<>();
            for (EndpointConfig ep : endpoints) {
                if (!ep.isConfigured()) continue;
                String epNameFormat = config.getOrDefault("endpoint." + ep.getIndex() + ".saml.nameformat", AttributeStatementHelper.BASIC);
                for (MappingRule rule : ep.getMappingRules()) {
                    claimNameFormatMap.put(rule.getClaimName(), epNameFormat);
                }
            }

            for (Map.Entry<String, Object> entry : claims.entrySet()) {
                String claimName = entry.getKey();
                String nameFormat = claimNameFormatMap.getOrDefault(claimName, AttributeStatementHelper.BASIC);
                Object claimValue = entry.getValue();

                AttributeType attribute = new AttributeType(claimName);
                attribute.setNameFormat(nameFormat);
                
                if (claimValue instanceof Collection) {
                    for (Object val : (Collection<?>) claimValue) {
                        if (val != null) {
                            attribute.addAttributeValue(val.toString());
                        }
                    }
                } else {
                    if (claimValue != null) {
                        attribute.addAttributeValue(claimValue.toString());
                    }
                }

                attributeStatement.addAttribute(new AttributeStatementType.ASTChoiceType(attribute));
            }

        } catch (Exception e) {
            LOG.errorf(e, "SamlRestClaimMapper: unexpected error — claims skipped for session %s", userSession.getId());
        }
    }
}
