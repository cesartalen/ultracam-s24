package dev.phonecam

import android.net.Uri
import android.os.Build
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID
import kotlin.concurrent.thread

private val soap = ContentType.parse("application/soap+xml")

fun Route.onvif(svc: CamService) {
    post("/onvif/{service}") {
        val body = call.receiveText()
        val action = Regex("Body>\\s*<(?:\\w+:)?(\\w+)").find(body)?.groupValues?.get(1)
        val token = Regex("ProfileToken>([^<]*)<").find(body)?.groupValues?.get(1).orEmpty()
        val ip = ip()
        val response = when (action) {
            "GetSystemDateAndTime" -> dateTime()
            "GetDeviceInformation" -> deviceInfo(svc.serial())
            "GetCapabilities" -> capabilities(ip)
            "GetServices" -> services(ip)
            "GetVideoSources" -> videoSources(svc.state)
            "GetProfiles" -> profiles(svc.state)
            "GetStreamUri" -> uri("GetStreamUri", "rtsp://$ip:${if (token == "sub") 8555 else 8554}/live")
            "GetSnapshotUri" -> uri("GetSnapshotUri", "http://$ip:8080/snapshot.jpg")
            else -> null
        }
        if (response == null) {
            call.respondText(envelope(fault), soap, HttpStatusCode.BadRequest)
        } else {
            call.respondText(envelope(response), soap)
        }
    }
}

class Discovery(serial: String) {
    private val urn = "urn:uuid:${UUID.nameUUIDFromBytes(serial.toByteArray())}"
    private val socket = MulticastSocket(3702)
    private val thread = thread(start = false, name = "wsd") { loop() }

    fun start() {
        val group = InetAddress.getByName("239.255.255.250")
        socket.joinGroup(InetSocketAddress(group, 3702), NetworkInterface.getByInetAddress(InetAddress.getByName(ip())))
        thread.start()
    }

    fun stop() = socket.close()

    private fun loop() {
        val buf = ByteArray(8192)
        while (true) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
            } catch (e: IOException) {
                return
            }
            val text = String(buf, 0, packet.length)
            val types = Regex("Types>([^<]*)<").find(text)?.groupValues?.get(1).orEmpty()
            if ("Probe" !in text || types.isNotBlank() && "NetworkVideoTransmitter" !in types && "Device" !in types) continue
            val id = Regex("MessageID>([^<]*)<").find(text)?.groupValues?.get(1).orEmpty()
            val reply = match(id).toByteArray()
            socket.send(DatagramPacket(reply, reply.size, packet.socketAddress))
        }
    }

    private fun match(relatesTo: String) =
        """<?xml version="1.0" encoding="UTF-8"?>
        <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:a="http://schemas.xmlsoap.org/ws/2004/08/addressing"
          xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
        <s:Header><a:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/ProbeMatches</a:Action>
        <a:MessageID>urn:uuid:${UUID.randomUUID()}</a:MessageID><a:RelatesTo>$relatesTo</a:RelatesTo>
        <a:To>http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</a:To></s:Header>
        <s:Body><d:ProbeMatches><d:ProbeMatch><a:EndpointReference><a:Address>$urn</a:Address></a:EndpointReference>
        <d:Types>dn:NetworkVideoTransmitter</d:Types>
        <d:Scopes>onvif://www.onvif.org/type/video_encoder onvif://www.onvif.org/Profile/Streaming onvif://www.onvif.org/name/phonecam
          onvif://www.onvif.org/hardware/${Uri.encode(Build.MODEL)} onvif://www.onvif.org/location/</d:Scopes>
        <d:XAddrs>http://${ip()}:8080/onvif/device_service</d:XAddrs><d:MetadataVersion>1</d:MetadataVersion>
        </d:ProbeMatch></d:ProbeMatches></s:Body></s:Envelope>"""
}

private fun envelope(body: String) =
    """<?xml version="1.0" encoding="UTF-8"?>
    <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:tds="http://www.onvif.org/ver10/device/wsdl"
      xmlns:trt="http://www.onvif.org/ver10/media/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema"
      xmlns:ter="http://www.onvif.org/ver10/error">
    <s:Body>$body</s:Body></s:Envelope>"""

private val fault =
    """<s:Fault><s:Code><s:Value>s:Sender</s:Value><s:Subcode><s:Value>ter:ActionNotSupported</s:Value></s:Subcode></s:Code>
    <s:Reason><s:Text xml:lang="en">not supported</s:Text></s:Reason></s:Fault>"""

private fun dateTime(): String {
    val t = ZonedDateTime.now(ZoneOffset.UTC)
    return """<tds:GetSystemDateAndTimeResponse><tds:SystemDateAndTime><tt:DateTimeType>NTP</tt:DateTimeType>
    <tt:DaylightSavings>false</tt:DaylightSavings><tt:TimeZone><tt:TZ>UTC</tt:TZ></tt:TimeZone>
    <tt:UTCDateTime><tt:Time><tt:Hour>${t.hour}</tt:Hour><tt:Minute>${t.minute}</tt:Minute><tt:Second>${t.second}</tt:Second></tt:Time>
    <tt:Date><tt:Year>${t.year}</tt:Year><tt:Month>${t.monthValue}</tt:Month><tt:Day>${t.dayOfMonth}</tt:Day></tt:Date></tt:UTCDateTime>
    </tds:SystemDateAndTime></tds:GetSystemDateAndTimeResponse>"""
}

private fun deviceInfo(serial: String) =
    """<tds:GetDeviceInformationResponse><tds:Manufacturer>${Build.MANUFACTURER}</tds:Manufacturer><tds:Model>${Build.MODEL}</tds:Model>
    <tds:FirmwareVersion>phonecam</tds:FirmwareVersion><tds:SerialNumber>$serial</tds:SerialNumber><tds:HardwareId>phonecam</tds:HardwareId>
    </tds:GetDeviceInformationResponse>"""

private fun capabilities(ip: String) =
    """<tds:GetCapabilitiesResponse><tds:Capabilities>
    <tt:Device><tt:XAddr>http://$ip:8080/onvif/device_service</tt:XAddr>
    <tt:Network><tt:IPFilter>false</tt:IPFilter><tt:ZeroConfiguration>false</tt:ZeroConfiguration><tt:IPVersion6>false</tt:IPVersion6>
    <tt:DynDNS>false</tt:DynDNS></tt:Network>
    <tt:System><tt:DiscoveryResolve>false</tt:DiscoveryResolve><tt:DiscoveryBye>false</tt:DiscoveryBye>
    <tt:RemoteDiscovery>false</tt:RemoteDiscovery><tt:SystemBackup>false</tt:SystemBackup>
    <tt:SystemLogging>false</tt:SystemLogging><tt:FirmwareUpgrade>false</tt:FirmwareUpgrade>
    <tt:SupportedVersions><tt:Major>2</tt:Major><tt:Minor>0</tt:Minor></tt:SupportedVersions></tt:System>
    <tt:IO><tt:InputConnectors>0</tt:InputConnectors><tt:RelayOutputs>0</tt:RelayOutputs></tt:IO>
    <tt:Security><tt:TLS1.1>false</tt:TLS1.1><tt:TLS1.2>false</tt:TLS1.2><tt:OnboardKeyGeneration>false</tt:OnboardKeyGeneration>
    <tt:AccessPolicyConfig>false</tt:AccessPolicyConfig><tt:X.509Token>false</tt:X.509Token><tt:SAMLToken>false</tt:SAMLToken>
    <tt:KerberosToken>false</tt:KerberosToken><tt:RELToken>false</tt:RELToken></tt:Security></tt:Device>
    <tt:Media><tt:XAddr>http://$ip:8080/onvif/media_service</tt:XAddr><tt:StreamingCapabilities><tt:RTPMulticast>false</tt:RTPMulticast>
    <tt:RTP_TCP>true</tt:RTP_TCP><tt:RTP_RTSP_TCP>true</tt:RTP_RTSP_TCP></tt:StreamingCapabilities></tt:Media>
    </tds:Capabilities></tds:GetCapabilitiesResponse>"""

private fun services(ip: String) =
    """<tds:GetServicesResponse>
    <tds:Service><tds:Namespace>http://www.onvif.org/ver10/device/wsdl</tds:Namespace>
    <tds:XAddr>http://$ip:8080/onvif/device_service</tds:XAddr>
    <tds:Version><tt:Major>2</tt:Major><tt:Minor>0</tt:Minor></tds:Version></tds:Service>
    <tds:Service><tds:Namespace>http://www.onvif.org/ver10/media/wsdl</tds:Namespace>
    <tds:XAddr>http://$ip:8080/onvif/media_service</tds:XAddr>
    <tds:Version><tt:Major>2</tt:Major><tt:Minor>0</tt:Minor></tds:Version></tds:Service>
    </tds:GetServicesResponse>"""

private fun videoSources(st: JSONObject) =
    """<trt:GetVideoSourcesResponse><trt:VideoSources token="vs"><tt:Framerate>${st.getInt("fps")}</tt:Framerate>
    <tt:Resolution><tt:Width>${st.getInt("width")}</tt:Width><tt:Height>${st.getInt("height")}</tt:Height></tt:Resolution>
    </trt:VideoSources></trt:GetVideoSourcesResponse>"""

private fun profiles(st: JSONObject): String {
    val w = st.getInt("width")
    val h = st.getInt("height")
    val fps = st.getInt("fps")
    return "<trt:GetProfilesResponse>" +
        profile("main", w, h, fps, st.getInt("bitrate"), w, h) +
        profile("sub", 640, 360, fps, 800, w, h) +
        "</trt:GetProfilesResponse>"
}

private fun profile(token: String, w: Int, h: Int, fps: Int, kbps: Int, srcW: Int, srcH: Int) =
    """<trt:Profiles token="$token" fixed="true"><tt:Name>$token</tt:Name>
    <tt:VideoSourceConfiguration token="vsc"><tt:Name>vsc</tt:Name><tt:UseCount>2</tt:UseCount><tt:SourceToken>vs</tt:SourceToken>
    <tt:Bounds x="0" y="0" width="$srcW" height="$srcH"/></tt:VideoSourceConfiguration>
    <tt:VideoEncoderConfiguration token="vec-$token"><tt:Name>vec-$token</tt:Name><tt:UseCount>1</tt:UseCount>
    <tt:Encoding>H264</tt:Encoding>
    <tt:Resolution><tt:Width>$w</tt:Width><tt:Height>$h</tt:Height></tt:Resolution><tt:Quality>5</tt:Quality>
    <tt:RateControl><tt:FrameRateLimit>$fps</tt:FrameRateLimit><tt:EncodingInterval>1</tt:EncodingInterval>
    <tt:BitrateLimit>$kbps</tt:BitrateLimit></tt:RateControl>
    <tt:H264><tt:GovLength>$fps</tt:GovLength><tt:H264Profile>Main</tt:H264Profile></tt:H264>
    <tt:Multicast><tt:Address><tt:Type>IPv4</tt:Type><tt:IPv4Address>0.0.0.0</tt:IPv4Address></tt:Address>
    <tt:Port>0</tt:Port><tt:TTL>0</tt:TTL>
    <tt:AutoStart>false</tt:AutoStart></tt:Multicast><tt:SessionTimeout>PT60S</tt:SessionTimeout></tt:VideoEncoderConfiguration>
    </trt:Profiles>"""

private fun uri(name: String, value: String) =
    """<trt:${name}Response><trt:MediaUri><tt:Uri>$value</tt:Uri><tt:InvalidAfterConnect>false</tt:InvalidAfterConnect>
    <tt:InvalidAfterReboot>false</tt:InvalidAfterReboot><tt:Timeout>PT60S</tt:Timeout></trt:MediaUri></trt:${name}Response>"""
