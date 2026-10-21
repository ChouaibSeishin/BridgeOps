package com.bridgeops.integrations.domain;

import com.bridgeops.shared.web.DomainFailure;
import java.net.*;
import java.util.*;

/** Production policy. There is deliberately no switch to allow private networks. */
public final class DestinationPolicy {
    private final Set<String> allowedHosts;
    public DestinationPolicy(Collection<String> hosts){
        var checked=new HashSet<String>();for(String host:hosts){if(!hostname(host))throw new IllegalArgumentException("Invalid outbound allowlist.");checked.add(host.toLowerCase(Locale.ROOT));}allowedHosts=Set.copyOf(checked);
    }
    public URI base(String value){
        try {
            var uri=new URI(value);
            if(!"https".equals(uri.getScheme())||!hostname(uri.getHost())||!allowedHosts.contains(uri.getHost().toLowerCase(Locale.ROOT))
                ||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||(uri.getPort()!=-1&&uri.getPort()!=443)
                ||!Set.of("","/").contains(uri.getRawPath()))throw denied();
            return new URI("https",null,uri.getHost().toLowerCase(Locale.ROOT),-1,"/",null,null);
        }catch(URISyntaxException|NullPointerException invalid){throw denied();}
    }
    public String path(String value){
        // Literal registered paths only. Parameters are individual allowlisted alphanumeric segments.
        if(value==null||value.length()>500||!value.matches("/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]*"))throw denied();return value;
    }
    public InetAddress[] answers(String hostname,InetAddress[] addresses){
        if(!allowedHosts.contains(hostname.toLowerCase(Locale.ROOT))||addresses==null||addresses.length==0||addresses.length>16)throw denied();
        for(InetAddress address:addresses)if(address==null||!publicAddress(address))throw denied();return addresses.clone();
    }
    static boolean hostname(String name){return name!=null&&name.length()<=253&&name.equals(name.toLowerCase(Locale.ROOT))&&name.matches("(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z][a-z0-9-]{1,62}");}
    public static boolean publicAddress(InetAddress address){
        if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress())return false;
        byte[] b=address.getAddress();
        if(b.length==4){long n=0;for(byte v:b)n=(n<<8)|(v&255L);
            for(String network:List.of("0.0.0.0/8","10.0.0.0/8","100.64.0.0/10","127.0.0.0/8","169.254.0.0/16","172.16.0.0/12","192.0.0.0/24","192.0.2.0/24","192.88.99.0/24","192.168.0.0/16","198.18.0.0/15","198.51.100.0/24","203.0.113.0/24","224.0.0.0/4","240.0.0.0/4")){
                String[] parts=network.split("/");long base=0;for(String octet:parts[0].split("\\."))base=(base<<8)|Integer.parseInt(octet);long mask=(0xffffffffL<<(32-Integer.parseInt(parts[1])))&0xffffffffL;if((n&mask)==base)return false;
            }return true;
        }
        if(b.length!=16||(b[0]&0xe0)!=0x20)return false; // Global unicast only; excludes ULA, NAT64, mapped and scoped addresses.
        if((b[0]&255)==0x20&&(b[1]&255)==1&&((b[2]&255)<2||((b[2]&255)==0x0d&&(b[3]&255)==0xb8)))return false;
        if((b[0]&255)==0x20&&(b[1]&255)==2)return false; // 6to4 encapsulation.
        if((b[0]&255)==0x3f&&((b[1]&255)==0xfe||((b[1]&255)==0xff&&(b[2]&0xf0)==0)))return false;
        return address instanceof Inet6Address v6&&v6.getScopeId()==0;
    }
    private static DomainFailure denied(){return new DomainFailure(422,"DESTINATION_NOT_ALLOWED");}
}
