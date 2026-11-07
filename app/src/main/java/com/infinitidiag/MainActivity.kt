
package com.infinitidiag

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
private val Navy = Color(0xFF0B1220)
private val Panel = Color(0xFF151F30)
private val Panel2 = Color(0xFF1D2A3D)
private val Accent = Color(0xFF27D7A1)
private val TextMain = Color(0xFFEAF1FA)
private val TextMuted = Color(0xFF9BAAC0)
private val Warning = Color(0xFFFFB547)
private val Red = Color(0xFFFF647C)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { InfinitiDiagUX() }
    }
}

private data class VehicleModule(val name: String, val detail: String, val status: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
private fun InfinitiDiagUX() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val adapter = remember { BluetoothAdapter.getDefaultAdapter() }
    fun hasBluetoothPermissions(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
             ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED)

    var permission by remember { mutableStateOf(hasBluetoothPermissions()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> permission = results.values.all { it } && hasBluetoothPermissions() }
    var paired by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var chosen by remember { mutableStateOf<BluetoothDevice?>(null) }
    var socket by remember { mutableStateOf<BluetoothSocket?>(null) }
    var isConnected by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Adapter not connected") }
    var screen by remember { mutableStateOf("Home") }
    var log by remember { mutableStateOf("InfinitiDiag diagnostic session initialized.") }
    var command by remember { mutableStateOf("0100") }
    var scanRunning by remember { mutableStateOf(false) }
    var scanDone by remember { mutableStateOf(false) }
    var moduleStatus by remember { mutableStateOf(mapOf<String,String>()) }
    var rpm by remember { mutableStateOf("--") }
    var coolant by remember { mutableStateOf("--") }
    var speed by remember { mutableStateOf("--") }
    var maf by remember { mutableStateOf("--") }
    var load by remember { mutableStateOf("--") }
    var voltage by remember { mutableStateOf("--") }
    var liveOn by remember { mutableStateOf(false) }

    val modules = listOf(
        VehicleModule("Engine / ECM", "Powertrain control", "OBD engine access available", Icons.Default.Settings),
        VehicleModule("Transmission / TCM", "Automatic transmission", "Not yet verified", Icons.Default.Sync),
        VehicleModule("ABS / VDC", "Braking and stability", "Not yet verified", Icons.Default.Shield),
        VehicleModule("AWD / ATTESA", "All wheel drive", "Not yet verified", Icons.Default.DirectionsCar),
        VehicleModule("Airbag / SRS", "Supplemental restraint", "Not yet verified", Icons.Default.Warning),
        VehicleModule("BCM", "Body control", "Not yet verified", Icons.Default.Bolt),
        VehicleModule("IPDM", "Power distribution", "Not yet verified", Icons.Default.ElectricalServices),
        VehicleModule("Instrument cluster", "Meter and display", "Not yet verified", Icons.Default.Speed),
        VehicleModule("HVAC", "Climate control", "Not yet verified", Icons.Default.AcUnit),
        VehicleModule("Navigation / AV", "Audio and navigation", "Not yet verified", Icons.Default.Map)
    )

    fun addLog(s:String) { log = (log + "\n" + s).takeLast(9000) }

    fun refresh() {
        if (!permission) {
            if (Build.VERSION.SDK_INT >= 31) permissionLauncher.launch(arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            ))
            return
        }
        try {
            paired = adapter?.bondedDevices?.toList() ?: emptyList()
            if (chosen == null) chosen = paired.firstOrNull()
            status = "${paired.size} paired Bluetooth device(s)"
        } catch (_: Exception) { status = "Bluetooth permission unavailable" }
    }

    suspend fun send(cmd:String, timeout:Long=1800):String {
        val s = socket ?: return "ERROR: Not connected"
        return withContext(Dispatchers.IO) {
            try {
                val input=s.inputStream; val output=s.outputStream
                while(input.available()>0) input.read()
                output.write((cmd.trim().uppercase()+"\r").toByteArray(Charsets.US_ASCII)); output.flush()
                val out=StringBuilder(); val b=ByteArray(256); val end=System.currentTimeMillis()+timeout
                while(System.currentTimeMillis()<end) {
                    if(input.available()>0) {
                        val n=input.read(b); if(n>0) { out.append(String(b,0,n,Charsets.US_ASCII)); if(out.contains(">")) break }
                    } else delay(30)
                }
                out.toString().trim().ifBlank { "(no response)" }
            } catch(e:Exception) { "ERROR: ${e.message}" }
        }
    }

    fun connect() {
        if (!permission) { if(Build.VERSION.SDK_INT>=31) permissionLauncher.launch(arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )); return }
        val device=chosen ?: run { status="Select a paired adapter"; return }
        connecting=true; status="Connecting to adapter..."
        scope.launch {
            var connectionError = "Unknown Bluetooth error"
            val s=withContext(Dispatchers.IO) {
                try {
                    adapter?.cancelDiscovery()
                    // Try authenticated RFCOMM first, then unauthenticated SPP.
                    // Some OBD adapters expose SPP but reject the secure socket.
                    try {
                        device.createRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
                    } catch (secureError: Exception) {
                        connectionError = "Secure RFCOMM failed: ${secureError.javaClass.simpleName}: ${secureError.message}"
                        try {
                            device.createInsecureRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
                        } catch (insecureError: Exception) {
                            connectionError += " | Insecure RFCOMM failed: ${insecureError.javaClass.simpleName}: ${insecureError.message}"
                            null
                        }
                    }
                } catch (e: Exception) {
                    connectionError = "${e.javaClass.simpleName}: ${e.message}"
                    null
                }
            }
            connecting=false
            if(s!=null) {
                socket=s; isConnected=true
                status="Connected • ${try{device.name ?: device.address}catch(_:Exception){"vLinker"}}"
                addLog("Bluetooth connected using RFCOMM.")
                listOf("ATZ","ATE0","ATL0","ATS1","ATH1","ATSP6").forEach { addLog("$it → ${send(it,3000)}") }
            } else {
                status="Bluetooth failed: $connectionError"
                addLog("Bluetooth connection failed: $connectionError")
            }
        }
    }
    fun disconnect() {
        liveOn=false; try{socket?.close()}catch(_:Exception){}
        socket=null; isConnected=false; status="Adapter disconnected"
    }

    fun readLive() {
        if(!isConnected || liveOn) return
        liveOn=true
        scope.launch {
            while(liveOn && isConnected) {
                val r=send("010C"); val tokens=r.uppercase().replace(Regex("[^0-9A-F ]")," ").split(Regex("\\s+")).filter{it.matches(Regex("[0-9A-F]{2}"))}
                val i=tokens.indexOf("41")
                if(i>=0 && tokens.size>i+3 && tokens[i+1]=="0C") {
                    val a=tokens[i+2].toInt(16); val b=tokens[i+3].toInt(16); rpm=((a*256+b)/4).toString()
                }
                val t=send("0105"); val tt=t.uppercase().replace(Regex("[^0-9A-F ]")," ").split(Regex("\\s+")).filter{it.matches(Regex("[0-9A-F]{2}"))}
                val ti=tt.indexOf("41"); if(ti>=0 && tt.size>ti+2 && tt[ti+1]=="05") coolant=(tt[ti+2].toInt(16)-40).toString()
                val v=send("010D"); val vt=v.uppercase().replace(Regex("[^0-9A-F ]")," ").split(Regex("\\s+")).filter{it.matches(Regex("[0-9A-F]{2}"))}
                val vi=vt.indexOf("41"); if(vi>=0 && vt.size>vi+2 && vt[vi+1]=="0D") speed=vt[vi+2].toInt(16).toString()
                val m=send("0110"); val mt=m.uppercase().replace(Regex("[^0-9A-F ]")," ").split(Regex("\\s+")).filter{it.matches(Regex("[0-9A-F]{2}"))}
                val mi=mt.indexOf("41"); if(mi>=0 && mt.size>mi+3 && mt[mi+1]=="10") maf=String.format("%.2f",(mt[mi+2].toInt(16)*256+mt[mi+3].toInt(16))/100.0)
                val l=send("0104"); val lt=l.uppercase().replace(Regex("[^0-9A-F ]")," ").split(Regex("\\s+")).filter{it.matches(Regex("[0-9A-F]{2}"))}
                val li=lt.indexOf("41"); if(li>=0 && lt.size>li+2 && lt[li+1]=="04") load=String.format("%.1f",lt[li+2].toInt(16)*100.0/255.0)
                delay(700)
            }
        }
    }

    MaterialTheme(colorScheme=darkColorScheme(primary=Accent, background=Navy, surface=Panel, onSurface=TextMain, onBackground=TextMain)) {
        Scaffold(
            containerColor=Navy,
            bottomBar={
                NavigationBar(containerColor=Panel) {
                    listOf("Home","Modules","Live Data","Scan","Settings").forEach { item ->
                        NavigationBarItem(selected=screen==item, onClick={screen=item},
                            icon={Icon(when(item){"Home"->Icons.Default.Home;"Modules"->Icons.Default.Memory;"Live Data"->Icons.Default.ShowChart;"Scan"->Icons.Default.Search;else->Icons.Default.Settings},contentDescription=item)},
                            label={Text(item)})
                    }
                }
            }
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal=16.dp), verticalArrangement=Arrangement.spacedBy(12.dp), contentPadding=PaddingValues(top=14.dp,bottom=24.dp)) {
                item {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("INFINITIDIAG", fontSize=22.sp, fontWeight=FontWeight.Bold, letterSpacing=1.5.sp)
                            Text("NISSAN FX35 • VQ35HR • 2011", color=TextMuted, fontSize=12.sp)
                        }
                        Surface(shape=RoundedCornerShape(50), color=if(isConnected) Color(0xFF123E35) else Panel2) {
                            Row(Modifier.padding(horizontal=10.dp,vertical=7.dp), verticalAlignment=Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).background(if(isConnected) Accent else Warning,CircleShape))
                                Spacer(Modifier.width(6.dp)); Text(if(isConnected)"ONLINE" else "OFFLINE",fontSize=11.sp,color=if(isConnected) Accent else Warning)
                            }
                        }
                    }
                }
                item {
                    Card(colors=CardDefaults.cardColors(containerColor=Panel), shape=RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Icon(Icons.Default.DirectionsCar,null,tint=Accent,modifier=Modifier.size(30.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("2011 Infiniti FX35 AWD",fontWeight=FontWeight.SemiBold)
                                    Text("VQ35HR • Vehicle profile",color=TextMuted,fontSize=12.sp)
                                }
                                Text("EDIT",color=Accent,fontSize=11.sp)
                            }
                            Divider(color=Panel2)
                            Text(status,color=TextMuted,fontSize=12.sp)
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                Button(onClick={refresh()}, modifier=Modifier.weight(1f)) { Text("Find adapter") }
                                Button(onClick={if(isConnected)disconnect() else connect()}, enabled=!connecting, modifier=Modifier.weight(1f)) { Text(if(isConnected)"Disconnect" else "Connect") }
                            }
                            if(paired.isNotEmpty()) {
                                Text("Paired devices",fontSize=12.sp,color=TextMuted)
                                paired.forEach { d ->
                                    val label=try{d.name ?: d.address}catch(_:Exception){"Bluetooth device"}
                                    Row(verticalAlignment=Alignment.CenterVertically) {
                                        RadioButton(selected=chosen==d,onClick={chosen=d})
                                        Text(label,fontSize=13.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                when(screen) {
                    "Home" -> {
                        item { SectionHeader("Vehicle health","Current session") }
                        item {
                            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                MetricCard("ECU","Connected",if(isConnected)Accent else Warning,Modifier.weight(1f))
                                MetricCard("DTCs","Not scanned",Warning,Modifier.weight(1f))
                                MetricCard("Modules","Unverified",TextMuted,Modifier.weight(1f))
                            }
                        }
                        item { SectionHeader("Quick access","") }
                        item {
                            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                QuickCard("Full system scan","Discover accessible diagnostic endpoints",Icons.Default.Search){screen="Scan"}
                                QuickCard("Live engine data","Monitor standard OBD-II values",Icons.Default.ShowChart){screen="Live Data"}
                                QuickCard("Control modules","View module coverage and access status",Icons.Default.Memory){screen="Modules"}
                            }
                        }
                        item { SectionHeader("Recent activity","") }
                        item { Card(colors=CardDefaults.cardColors(containerColor=Panel)) { Column(Modifier.padding(14.dp)) {
                            Text("Session ready",fontWeight=FontWeight.SemiBold)
                            Text("Connect your vLinker MC+ to begin. Module access is not confirmed until a response is received.",color=TextMuted,fontSize=12.sp)
                        } } }
                    }
                    "Modules" -> {
                        item { SectionHeader("Control modules","Access status") }
                        items(modules) { module ->
                            Card(colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(14.dp)) {
                                Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
                                    Surface(color=Panel2,shape=RoundedCornerShape(10.dp)) { Icon(module.icon,null,tint=Accent,modifier=Modifier.padding(10.dp).size(22.dp)) }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(module.name,fontWeight=FontWeight.SemiBold)
                                        Text(module.detail,color=TextMuted,fontSize=12.sp)
                                        Text(moduleStatus[module.name] ?: module.status,color=if(module.name=="Engine / ECM")Accent else Warning,fontSize=11.sp)
                                    }
                                    Icon(Icons.Default.ChevronRight,null,tint=TextMuted)
                                }
                            }
                        }
                        item { Text("Modules listed are intended targets. Most Nissan-specific module access is not implemented/verified yet.",color=TextMuted,fontSize=11.sp) }
                    }
                    "Live Data" -> {
                        item { SectionHeader("Live engine data","Generic OBD-II PIDs") }
                        item { Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            GaugeCard("RPM",rpm,"rpm",Modifier.weight(1f))
                            GaugeCard("Coolant",coolant,"°C",Modifier.weight(1f))
                        } }
                        item { Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            GaugeCard("Speed",speed,"km/h",Modifier.weight(1f))
                            GaugeCard("MAF",maf,"g/s",Modifier.weight(1f))
                        } }
                        item { GaugeCard("Engine load",load,"%") }
                        item { Button(onClick={if(liveOn) liveOn=false else readLive()},enabled=isConnected,modifier=Modifier.fillMaxWidth()) { Text(if(liveOn)"Stop live monitoring" else "Start live monitoring") } }
                        item { Text("Values depend on ECU PID support. Do not operate the app while driving.",color=TextMuted,fontSize=11.sp) }
                    }
                    "Scan" -> {
                        item { SectionHeader("Vehicle scan","Read-only scan") }
                        item { Card(colors=CardDefaults.cardColors(containerColor=Panel)) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text("Scan standard OBD-II engine ECU",fontWeight=FontWeight.SemiBold)
                            Text("Checks generic OBD engine communication. This is not a full CONSULT scan of all modules.",color=TextMuted,fontSize=12.sp)
                            Button(enabled=isConnected && !scanRunning,onClick={
                                scanRunning=true; scope.launch {
                                    val resp=send("0100")
                                    moduleStatus=moduleStatus+("Engine / ECM" to if(resp.contains("41 00")||resp.contains("4100"))"Responded to standard OBD-II" else "No standard PID response")
                                    addLog("OBD PID support query:\n$resp")
                                    scanDone=true; scanRunning=false
                                }
                            },modifier=Modifier.fillMaxWidth()) { Text(if(scanRunning)"Scanning..." else "Run engine ECU scan") }
                            if(scanDone) Text("Engine ECU scan completed. Check log for raw response.",color=Accent)
                        } } }
                    }
                    else -> {
                        item { SectionHeader("Settings","Diagnostics configuration") }
                        item { Card(colors=CardDefaults.cardColors(containerColor=Panel)) { Column(Modifier.padding(14.dp)) {
                            Text("Adapter: Bluetooth Classic SPP")
                            Text("Protocol setup: ELM auto / CAN 11-bit 500 kbps",color=TextMuted,fontSize=12.sp)
                            Text("VIN decoding, CONSULT profile, and advanced module protocols are planned.",color=TextMuted,fontSize=12.sp)
                        } } }
                        item { SectionHeader("Communication log","") }
                        item { Text(log,color=TextMuted,fontSize=11.sp) }
                        item { OutlinedTextField(command,{command=it},label={Text("Raw ELM command")},modifier=Modifier.fillMaxWidth()) }
                        item { Button(enabled=isConnected,onClick={scope.launch{addLog("> $command\n${send(command)}")}}){Text("Send command")} }
                    }
                }
                item { Text("InfinitiDiag • Prototype diagnostic software • Module coverage subject to protocol and adapter support",color=TextMuted,fontSize=10.sp) }
            }
        }
    }
}

@Composable private fun SectionHeader(title:String,subtitle:String) {
    Row(verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title,fontSize=18.sp,fontWeight=FontWeight.Bold); if(subtitle.isNotBlank()) Text(subtitle,color=TextMuted,fontSize=11.sp) }
    }
}
@Composable private fun MetricCard(label:String,value:String,color:Color,modifier:Modifier=Modifier) {
    Card(modifier,colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(12.dp)) { Text(label,color=TextMuted,fontSize=11.sp); Text(value,color=color,fontWeight=FontWeight.Bold,fontSize=14.sp) }
    }
}
@Composable private fun GaugeCard(label:String,value:String,unit:String,modifier:Modifier=Modifier) {
    Card(modifier,colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(label,color=TextMuted,fontSize=12.sp)
            Spacer(Modifier.height(8.dp))
            Text(value,fontSize=28.sp,fontWeight=FontWeight.Bold,color=TextMain)
            Text(unit,color=Accent,fontSize=12.sp)
        }
    }
}
@Composable private fun QuickCard(title:String,desc:String,icon:androidx.compose.ui.graphics.vector.ImageVector,onClick:()->Unit) {
    Card(onClick=onClick,colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
            Icon(icon,null,tint=Accent,modifier=Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { Text(title,fontWeight=FontWeight.SemiBold); Text(desc,color=TextMuted,fontSize=12.sp) }
            Icon(Icons.Default.ChevronRight,null,tint=TextMuted)
        }
    }
}
