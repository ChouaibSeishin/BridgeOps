package com.bridgeops.integrations.application;

import com.bridgeops.integrations.infrastructure.*;
import com.bridgeops.shared.web.DomainFailure;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ConnectionChecks {
    private final ConnectionService connections;private final OutboundAdmission admission;private final SecretStore secrets;private final RestTransport transport;
    public ConnectionChecks(ConnectionService connections,OutboundAdmission admission,SecretStore secrets,RestTransport transport){this.connections=connections;this.admission=admission;this.secrets=secrets;this.transport=transport;}
    @Transactional(propagation=Propagation.NEVER)
    public ConnectionService.Summary check(UUID actor,UUID family,UUID org,UUID id,long expected){
        return check(actor,family,org,id,expected,false);
    }
    @Transactional(propagation=Propagation.NEVER)
    public ConnectionService.Summary check(UUID actor,UUID family,UUID org,UUID id,long expected,boolean effectsAcknowledged){
        var plan=connections.prepareCheck(actor,family,org,id,expected);
        if(!plan.type().equals("REST"))return connections.recordCheck(actor,family,org,id,expected,"AVAILABLE","INTERNAL_AVAILABLE");
        if(!effectsAcknowledged)throw new DomainFailure(422,"EFFECTS_ACKNOWLEDGEMENT_REQUIRED");
        UUID slot=admission.acquire(org,id);byte[] credential=null;String status="AVAILABLE",code="READ_CHECK_SUCCEEDED";
        try{
            plan=connections.prepareCheck(actor,family,org,id,expected);if(plan.credential()!=null)credential=secrets.resolve(org,plan.credential(),"REST_CREDENTIAL");
            try{transport.call(plan.base(),plan.operation(),JsonMapper.builder().build().readTree("{\"pathParameters\":{},\"fields\":{}}"),credential,UUID.randomUUID());}
            catch(RestFailure failure){status="UNAVAILABLE";code=failure.code();}
        }finally{if(credential!=null)Arrays.fill(credential,(byte)0);admission.release(org,slot);}
        return connections.recordCheck(actor,family,org,id,expected,status,code);
    }
}
