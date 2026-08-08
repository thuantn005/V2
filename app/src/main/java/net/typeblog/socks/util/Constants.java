package net.typeblog.socks.util;

public class Constants
{
    public static final String ROUTE_ALL = "all",
            ROUTE_CHN = "chn";

    private static final String INTENT_PREFIX = "SOCKS";
    public static final String INTENT_NAME = INTENT_PREFIX + "NAME",
            INTENT_SERVER = INTENT_PREFIX + "SERV",
            INTENT_PORT = INTENT_PREFIX + "PORT",
            INTENT_USERNAME = INTENT_PREFIX + "UNAME",
            INTENT_PASSWORD = INTENT_PREFIX + "PASSWD",
            INTENT_ROUTE = INTENT_PREFIX + "ROUTE",
            INTENT_DNS = INTENT_PREFIX + "DNS",
            INTENT_DNS_PORT = INTENT_PREFIX + "DNSPORT",
            INTENT_PER_APP = INTENT_PREFIX + "PERAPP",
            INTENT_APP_BYPASS = INTENT_PREFIX + "APPBYPASS",
            INTENT_APP_LIST = INTENT_PREFIX + "APPLIST",
            INTENT_IPV6_PROXY = INTENT_PREFIX + "IPV6",
            INTENT_UDP_GW = INTENT_PREFIX + "UDPGW",
            INTENT_BF_PAYLOAD = INTENT_PREFIX + "BFPAYLOAD",
            INTENT_BF_SNI = INTENT_PREFIX + "BFSNI",
            INTENT_BF_REGION = INTENT_PREFIX + "BFREGION",
            INTENT_BF_CORES = INTENT_PREFIX + "BFCORES",
            INTENT_BF_INJECT_TYPE = INTENT_PREFIX + "BFINJECT",
            INTENT_BF_PROTOCOLS = INTENT_PREFIX + "BFPROTO",
            INTENT_BF_WHITELIST = INTENT_PREFIX + "BFWL",
            INTENT_BF_FRONT = INTENT_PREFIX + "BFFRONT";

    public static final String PREF = "profile",
            PREF_PROFILE = "profile",
            PREF_LAST_PROFILE = "last_profile",
            PREF_SERVER_IP = "server_ip",
            PREF_SERVER_PORT = "server_port",
            PREF_IPV6_PROXY = "ipv6_proxy",
            PREF_UDP_PROXY = "udp_proxy",
            PREF_UDP_GW = "udp_gw",
            PREF_AUTH_USERPW = "auth_userpw",
            PREF_AUTH_USERNAME = "auth_username",
            PREF_AUTH_PASSWORD = "auth_password",
            PREF_ADV_ROUTE = "adv_route",
            PREF_ADV_DNS = "adv_dns",
            PREF_ADV_DNS_PORT = "adv_dns_port",
            PREF_ADV_PER_APP = "adv_per_app",
            PREF_ADV_APP_BYPASS = "adv_app_bypass",
            PREF_ADV_APP_LIST = "adv_app_list",
            PREF_ADV_AUTO_CONNECT = "adv_auto_connect",
            PREF_BF_PAYLOAD = "bf_payload",
            PREF_BF_SNI = "bf_sni",
            PREF_BF_REGION = "bf_region",
            PREF_BF_CORES = "bf_cores",
            PREF_BF_INJECT_TYPE = "bf_inject_type",
            PREF_BF_PROTOCOLS = "bf_protocols",
            PREF_BF_WHITELIST = "bf_whitelist",
            PREF_BF_FRONT = "bf_front";
}
