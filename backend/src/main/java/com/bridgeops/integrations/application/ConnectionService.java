package com.bridgeops.integrations.application;

import com.bridgeops.audit.application.IntegrationAudit;
import com.bridgeops.integrations.domain.RestOperation;
import com.bridgeops.integrations.infrastructure.RestTransport;
import com.bridgeops.integrations.persistence.ConnectionStore;
import com.bridgeops.organizations.application.TenantAuthorization;
import com.bridgeops.shared.web.DomainFailure;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ConnectionService {
    public record Operation(String id,String classification,boolean requiresApproval,Map<String,String> requestFields,Map<String,String> responseFields) {}
    public record Definition(String type,String name,String description,int implementationVersion,List<Operation> operations) {}
    public record Health(String status,String code,Instant checkedAt,Long revision) {}
    public record Summary(UUID id,String name,String description,String type,int implementationVersion,long revision,boolean enabled,Instant updatedAt,Health health) {}
    public record Managed(Summary connection,JsonNode configuration,boolean credentialConfigured) {}
    public record Page(List<Summary> items,int page,int size,long totalElements,long totalPages) {}
    record Plan(UUID organization,UUID id,long revision,String type,String base,RestOperation operation,UUID credential){@Override public String toString(){return "ConnectionPlan[REDACTED]";}}
    private final ConnectionStore store;private final TenantAuthorization authority;private final IntegrationAudit audit;private final SecretStore secrets;private final RestTransport transport;private final Clock clock;
    private final JsonMapper json=JsonMapper.builder().build();
    public ConnectionService(ConnectionStore store,TenantAuthorization authority,IntegrationAudit audit,SecretStore secrets,RestTransport transport,Clock clock){this.store=store;this.authority=authority;this.audit=audit;this.secrets=secrets;this.transport=transport;this.clock=clock;}
    @Transactional(readOnly=true,timeout=5) public List<Definition> definitions(UUID actor,UUID family,UUID org){authority.read(actor,family,org,"integration.read");return List.of(
        new Definition("SUPPORT","Support Service","Persisted tenant tickets with revision preconditions.",1,List.of(new Operation("list","READ",false,Map.of("status","STRING","priority","STRING","category","STRING"),Map.of("tickets","ARRAY","count","INTEGER")))),
        new Definition("TEAMS","Team Directory","Stable team identity and explicit category responsibility mappings.",1,List.of(new Operation("resolve","READ",false,Map.of("tickets","ARRAY"),Map.of("assignments","ARRAY","unmatched","ARRAY","count","INTEGER")))),
        new Definition("NOTIFICATIONS","Internal Notifications","Persisted internal demo delivery; never external email or SMS.",1,List.of()),
        new Definition("REST","Configured HTTPS","Registered operations on exact operator-approved public hosts.",1,List.of()));}
    @Transactional(readOnly=true,timeout=5) public Page list(UUID actor,UUID family,UUID org,int page,int size){authority.read(actor,family,org,"integration.read");bounds(page,size);long total=store.count(org);return new Page(store.list(org,page,size).stream().map(this::summary).toList(),page,size,total,(total+size-1)/size);}
    @Transactional(readOnly=true,timeout=5) public Summary get(UUID actor,UUID family,UUID org,UUID id){authority.read(actor,family,org,"integration.read");return summary(store.get(org,id,false));}
    @Transactional(readOnly=true,timeout=5) public Managed configuration(UUID actor,UUID family,UUID org,UUID id){authority.read(actor,family,org,"integration.manage");return managed(store.get(org,id,false));}
    @Transactional(readOnly=true,timeout=5) public List<Operation> operations(UUID actor,UUID family,UUID org,UUID id){authority.read(actor,family,org,"integration.read");var row=store.get(org,id,false);if(!row.type().equals("REST"))return definitions(actor,family,org).stream().filter(d->d.type().equals(row.type())).findFirst().orElseThrow().operations();return operations(json.readTree(row.configuration())).stream().map(o->new Operation(o.id(),o.classification(),o.classification().equals("WRITE"),o.requestFields(),o.responseFields())).toList();}
    @Transactional(timeout=10) public Managed create(UUID actor,UUID family,UUID org,String name,String description,String type,JsonNode configuration,String credential){
        authority.write(actor,family,org,"integration.manage");metadata(name,description);String config=configuration(type,configuration);UUID reference=credential(actor,family,org,type,credential);
        UUID id=store.create(org,actor,name.trim(),description,type,config,reference,clock.instant());var row=store.get(org,id,false);version(row);audit.write(org,actor,IntegrationAudit.Action.INTEGRATION_CREATED,id,0,clock.instant());return managed(row);
    }
    @Transactional(timeout=10) public Managed update(UUID actor,UUID family,UUID org,UUID id,long expected,String name,String description,boolean enabled,JsonNode configuration,String credential,boolean removeCredential){
        authority.write(actor,family,org,"integration.manage");var row=store.get(org,id,true);revision(row,expected);metadata(name,description);String config=configuration(row.type(),configuration);
        if(credential!=null&&removeCredential)throw new DomainFailure(400,"INVALID_REQUEST");UUID reference=removeCredential?null:row.credential();if(credential!=null)reference=credential(actor,family,org,row.type(),credential);
        store.update(row,name.trim(),description,enabled,config,reference,clock.instant());row=store.get(org,id,false);version(row);audit.write(org,actor,IntegrationAudit.Action.INTEGRATION_CHANGED,id,row.revision(),clock.instant());if(credential!=null||removeCredential)audit.write(org,actor,IntegrationAudit.Action.SECRET_REBOUND,id,row.revision(),clock.instant());return managed(row);
    }
    @Transactional(readOnly=true,timeout=5) Plan prepareCheck(UUID actor,UUID family,UUID org,UUID id,long expected){
        authority.read(actor,family,org,"integration.manage");var row=store.get(org,id,false);revision(row,expected);if(!row.status().equals("ENABLED"))throw new DomainFailure(409,"INTEGRATION_DISABLED");
        if(!row.type().equals("REST"))return new Plan(org,id,row.revision(),row.type(),null,null,null);
        var config=json.readTree(row.configuration());String health=config.path("healthOperation").asString("");var operation=operations(config).stream().filter(o->o.id().equals(health)&&o.classification().equals("READ")&&o.pathParameters().isEmpty()).findFirst().orElseThrow(()->new DomainFailure(422,"NO_SAFE_HEALTH_OPERATION"));
        return new Plan(org,id,row.revision(),row.type(),config.path("baseDestination").asString(),operation,row.credential());
    }
    @Transactional(timeout=5) Summary recordCheck(UUID actor,UUID family,UUID org,UUID id,long expected,String status,String code){authority.write(actor,family,org,"integration.manage");var row=store.get(org,id,false);store.health(row,expected,status,code,clock.instant());audit.write(org,actor,IntegrationAudit.Action.INTEGRATION_CHECKED,id,expected,clock.instant());return summary(store.get(org,id,false));}
    private UUID credential(UUID actor,UUID family,UUID org,String type,String credential){
        if(credential==null)return null;if(!type.equals("REST")||credential.length()<8||credential.length()>4096||!credential.matches("[A-Za-z0-9._~+/-]+=*"))throw new DomainFailure(422,"INVALID_CREDENTIAL_FORMAT");byte[] bytes=credential.getBytes(StandardCharsets.UTF_8);try{return secrets.put(actor,family,org,"REST_CREDENTIAL",bytes);}finally{Arrays.fill(bytes,(byte)0);}
    }
    private String configuration(String type,JsonNode value){
        if(type==null||!Set.of("SUPPORT","TEAMS","NOTIFICATIONS","REST").contains(type)||value==null||!value.isObject()||json.writeValueAsBytes(value).length>65536)throw new DomainFailure(422,"INVALID_INTEGRATION_CONFIGURATION");
        if(!type.equals("REST")){if(!value.isEmpty())throw new DomainFailure(422,"INVALID_INTEGRATION_CONFIGURATION");}
        else{
            if(!value.propertyNames().equals(Set.of("baseDestination","operations","healthOperation"))||!value.path("baseDestination").isString()||!(value.path("healthOperation").isString()||value.path("healthOperation").isNull()))throw new DomainFailure(422,"INVALID_INTEGRATION_CONFIGURATION");
            transport.destination(value.path("baseDestination").asString());var ops=operations(value);
            if(!value.path("healthOperation").isNull()&&ops.stream().noneMatch(o->o.id().equals(value.path("healthOperation").asString())&&o.classification().equals("READ")&&o.pathParameters().isEmpty()))throw new DomainFailure(422,"INVALID_INTEGRATION_CONFIGURATION");
        }return json.writeValueAsString(sorted(value));
    }
    private List<RestOperation> operations(JsonNode configuration){var values=configuration.path("operations");if(!values.isArray()||values.isEmpty()||values.size()>20)throw new DomainFailure(422,"INVALID_INTEGRATION_CONFIGURATION");var result=new ArrayList<RestOperation>();var ids=new HashSet<String>();for(var value:values){var op=RestOperation.parse(value);if(!ids.add(op.id()))throw new DomainFailure(422,"INVALID_INTEGRATION_CONFIGURATION");result.add(op);}return List.copyOf(result);}
    private void version(ConnectionStore.Row row){var fields=new TreeMap<String,Object>();fields.put("type",row.type());fields.put("implementationVersion",row.implementationVersion());fields.put("configuration",sorted(json.readTree(row.configuration())));fields.put("credentialReference",row.credential()==null?null:row.credential().toString());String value=json.writeValueAsString(fields);store.version(row,value,hash(value),clock.instant());}
    private JsonNode sorted(JsonNode value){if(value.isObject()){var sorted=json.createObjectNode();value.propertyNames().stream().sorted().forEach(k->sorted.set(k,sorted(value.get(k))));return sorted;}if(value.isArray()){var sorted=json.createArrayNode();value.forEach(v->sorted.add(sorted(v)));return sorted;}return value;}
    private String hash(String value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException();}}
    private Managed managed(ConnectionStore.Row row){return new Managed(summary(row),json.readTree(row.configuration()),row.credential()!=null);}
    private Summary summary(ConnectionStore.Row row){String state=row.checkedAt()==null?"NOT_CHECKED":row.checkedRevision()!=row.revision()?"STALE":row.health();return new Summary(row.id(),row.name(),row.description(),row.type(),row.implementationVersion(),row.revision(),row.status().equals("ENABLED"),row.updatedAt(),new Health(state,row.healthCode(),row.checkedAt(),row.checkedRevision()));}
    private static void metadata(String name,String description){if(name==null||name.isBlank()||name.length()>120||name.codePoints().anyMatch(Character::isISOControl)||description==null||description.length()>1000)throw new DomainFailure(400,"INVALID_REQUEST");}
    private static void revision(ConnectionStore.Row row,long expected){if(expected<0)throw new DomainFailure(400,"INVALID_REQUEST");if(expected!=row.revision()||expected==Long.MAX_VALUE)throw new DomainFailure(409,"STALE_REVISION");}
    private static void bounds(int page,int size){if(page<0||page>100000||size<1||size>100)throw new DomainFailure(400,"INVALID_REQUEST");}
}
