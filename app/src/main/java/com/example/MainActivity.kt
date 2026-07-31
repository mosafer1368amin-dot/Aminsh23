package com.example

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

// Core Model for Servers
data class ServerConfig(
    val id: String,
    val nameEn: String,
    val nameFa: String,
    val cityEn: String,
    val cityFa: String,
    val flag: String,
    val dns1: String,
    val dns2: String,
    val basePing: Int,
    val load: Int,
    val isAiOptimized: Boolean,
    val isGamingOptimized: Boolean
)

// Supported AI Platforms for Bypass
data class AiPlatform(
    val name: String,
    val domain: String,
    val icon: ImageVector,
    val descriptionEn: String,
    val descriptionFa: String
)

class MainActivity : ComponentActivity() {

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startBypassService()
        } else {
            Toast.makeText(this, "VPN permission is required to bypass restrictions.", Toast.LENGTH_LONG).show()
        }
    }

    // Static server reference for easy updates
    private var selectedServerState = mutableStateOf(servers[0])
    private var isAiEngineOptimized = mutableStateOf(true)
    private var isLanguagePersian = mutableStateOf(true) // Default to Persian as requested

    // Live state inputs for custom DNS
    private var customDns1 = mutableStateOf("178.22.122.100")
    private var customDns2 = mutableStateOf("185.51.200.2")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme(darkTheme = true) { // Force dark mode for cool futuristic look
                val isConnected by BypassVpnService.isConnected.collectAsStateWithLifecycle()
                val currentServerName by BypassVpnService.activeServerName.collectAsStateWithLifecycle()
                val queryCount by BypassVpnService.queryCount.collectAsStateWithLifecycle()
                
                MainScreen(
                    isConnected = isConnected,
                    currentServerName = currentServerName,
                    queryCount = queryCount,
                    selectedServer = selectedServerState.value,
                    isAiEngineOptimized = isAiEngineOptimized.value,
                    isPersian = isLanguagePersian.value,
                    customDns1 = customDns1.value,
                    customDns2 = customDns2.value,
                    onToggleConnection = {
                        if (isConnected) {
                            stopBypassService()
                        } else {
                            prepareAndConnect()
                        }
                    },
                    onServerSelected = { server ->
                        selectedServerState.value = server
                        if (isConnected) {
                            // Reconnect immediately with new server
                            startBypassService()
                        }
                    },
                    onToggleAiOptimization = { enabled ->
                        isAiEngineOptimized.value = enabled
                        if (enabled) {
                            // Automatically select the best AI unblocking server (Helsinki - Shecan)
                            val shecanServer = servers.find { it.id == "shecan" } ?: servers[0]
                            selectedServerState.value = shecanServer
                            if (isConnected) {
                                startBypassService()
                            }
                        }
                    },
                    onToggleLanguage = {
                        isLanguagePersian.value = !isLanguagePersian.value
                    },
                    onCustomDnsChanged = { d1, d2 ->
                        customDns1.value = d1
                        customDns2.value = d2
                        val customServer = selectedServerState.value.copy(dns1 = d1, dns2 = d2)
                        selectedServerState.value = customServer
                        if (isConnected && selectedServerState.value.id == "custom") {
                            startBypassService()
                        }
                    }
                )
            }
        }
    }

    private fun prepareAndConnect() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            startBypassService()
        }
    }

    private fun startBypassService() {
        val server = selectedServerState.value
        val intent = Intent(this, BypassVpnService::class.java).apply {
            action = BypassVpnService.ACTION_CONNECT
            putExtra(BypassVpnService.EXTRA_DNS1, if (server.id == "custom") customDns1.value else server.dns1)
            putExtra(BypassVpnService.EXTRA_DNS2, if (server.id == "custom") customDns2.value else server.dns2)
            putExtra(BypassVpnService.EXTRA_SERVER_NAME, if (isLanguagePersian.value) server.nameFa else server.nameEn)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopBypassService() {
        val intent = Intent(this, BypassVpnService::class.java).apply {
            action = BypassVpnService.ACTION_DISCONNECT
        }
        startService(intent)
    }
}

// Global server options
val servers = listOf(
    ServerConfig(
        id = "shecan",
        nameEn = "Finland (Helsinki - Shecan Route)",
        nameFa = "فنلاند (هلسینکی - مسیر شکن)",
        cityEn = "Helsinki",
        cityFa = "هلسینکی",
        flag = "🇫🇮",
        dns1 = "178.22.122.100",
        dns2 = "185.51.200.2",
        basePing = 42,
        load = 28,
        isAiOptimized = true,
        isGamingOptimized = false
    ),
    ServerConfig(
        id = "electro",
        nameEn = "Germany (Frankfurt - Electro Route)",
        nameFa = "آلمان (فرانکفورت - مسیر الکترو)",
        cityEn = "Frankfurt",
        cityFa = "فرانکفورت",
        flag = "🇩🇪",
        dns1 = "78.157.42.100",
        dns2 = "78.157.42.101",
        basePing = 35,
        load = 41,
        isAiOptimized = true,
        isGamingOptimized = true
    ),
    ServerConfig(
        id = "403online",
        nameEn = "Netherlands (Amsterdam - 403 Route)",
        nameFa = "هلند (آمستردام - مسیر ۴۰۳)",
        cityEn = "Amsterdam",
        cityFa = "آمستردام",
        flag = "🇳🇱",
        dns1 = "10.202.10.202",
        dns2 = "10.202.10.102",
        basePing = 46,
        load = 22,
        isAiOptimized = true,
        isGamingOptimized = false
    ),
    ServerConfig(
        id = "radar",
        nameEn = "UAE (Dubai - Radar Route)",
        nameFa = "امارات (دبی - مسیر رادار گیم)",
        cityEn = "Dubai",
        cityFa = "دبی",
        flag = "🇦🇪",
        dns1 = "10.201.10.201",
        dns2 = "10.201.10.101",
        basePing = 15,
        load = 64,
        isAiOptimized = false,
        isGamingOptimized = true
    ),
    ServerConfig(
        id = "cloudflare",
        nameEn = "USA (Virginia - Cloudflare Router)",
        nameFa = "آمریکا (ویرجینیا - کلودفلر)",
        cityEn = "Virginia",
        cityFa = "ویرجینیا",
        flag = "🇺🇸",
        dns1 = "1.1.1.1",
        dns2 = "1.0.0.1",
        basePing = 98,
        load = 12,
        isAiOptimized = false,
        isGamingOptimized = false
    ),
    ServerConfig(
        id = "custom",
        nameEn = "Custom DNS Tunnel",
        nameFa = "دی‌ان‌اس سفارشی ضد فیلتر",
        cityEn = "Manual Configuration",
        cityFa = "تنظیم دستی",
        flag = "⚙️",
        dns1 = "178.22.122.100",
        dns2 = "185.51.200.2",
        basePing = 45,
        load = 0,
        isAiOptimized = true,
        isGamingOptimized = false
    )
)

// Supported AI tools
val aiPlatforms = listOf(
    AiPlatform("ChatGPT", "chatgpt.com", Icons.Default.SmartToy, "ChatGPT & OpenAI API", "چت‌جی‌پی‌تی و ای‌پی‌آی اوپن‌ای‌آی"),
    AiPlatform("Google Gemini", "gemini.google.com", Icons.Default.AutoAwesome, "Gemini & Vertex AI Workspace", "گوگل جمینای و پلتفرم ورتکس"),
    AiPlatform("Claude AI", "claude.ai", Icons.Default.Psychology, "Anthropic Claude Models", "هوش مصنوعی کلود شرکت آنتروپیک"),
    AiPlatform("Midjourney", "midjourney.com", Icons.Default.Palette, "Generative Image AI Systems", "سیستم تصویرساز میدجورنی"),
    AiPlatform("Perplexity", "perplexity.ai", Icons.Default.Search, "AI-Powered Search & Research", "موتور جستجو و تحقیق پرپلکسیتی")
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
    isConnected: Boolean,
    currentServerName: String,
    queryCount: Int,
    selectedServer: ServerConfig,
    isAiEngineOptimized: Boolean,
    isPersian: Boolean,
    customDns1: String,
    customDns2: String,
    onToggleConnection: () -> Unit,
    onServerSelected: (ServerConfig) -> Unit,
    onToggleAiOptimization: (Boolean) -> Unit,
    onToggleLanguage: () -> Unit,
    onCustomDnsChanged: (String, String) -> Unit
) {
    var showServerSelector by remember { mutableStateOf(false) }
    var speedSim by remember { mutableStateOf(0.0) }
    var pingSim by remember { mutableStateOf(selectedServer.basePing) }
    val scope = rememberCoroutineScope()

    // Simulation of live speeds & latency
    LaunchedEffect(isConnected, selectedServer) {
        if (isConnected) {
            while (true) {
                // Vary speed slightly to look organic
                speedSim = Random.nextDouble(1.5, 8.4)
                pingSim = selectedServer.basePing + Random.nextInt(-3, 4)
                delay(1500)
            }
        } else {
            speedSim = 0.0
            pingSim = selectedServer.basePing
        }
    }

    // Force layouts to adapt to the language direction (RTL for Persian, LTR for English)
    val layoutDirection = if (isPersian) LayoutDirection.Rtl else LayoutDirection.Ltr

    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .testTag("main_scaffold"),
            containerColor = Color(0xFF0C0E14) // Deep high-tech dark background
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .drawBehind {
                        // Futuristic radial purple-blue glow background effect
                        val radialGradient = Brush.radialGradient(
                            colors = listOf(
                                Color(0x153A1C6A),
                                Color(0x00000000)
                            ),
                            center = Offset(size.width / 2, size.height / 3),
                            radius = size.width * 1.2f
                        )
                        drawRect(brush = radialGradient)
                    }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 1. Top Bar / App Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = "Security Shield",
                                tint = Color(0xFF00E5FF), // Cyber Cyan
                                modifier = Modifier
                                    .size(32.dp)
                                    .padding(end = 4.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isPersian) "شکن هوشمند" else "Smart Bypass",
                                fontSize = 21.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        // Language Toggle Button
                        Button(
                            onClick = onToggleLanguage,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1E2538),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("language_toggle")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = "Language Selector",
                                modifier = Modifier.size(16.dp),
                                tint = Color(0xFF00E5FF)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isPersian) "English" else "فارسی",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // 2. Beautiful connection button
                    Spacer(modifier = Modifier.height(10.dp))
                    ConnectionCircle(
                        isConnected = isConnected,
                        onClick = onToggleConnection,
                        isPersian = isPersian
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Connection status badge
                    StatusBadge(isConnected = isConnected, isPersian = isPersian)
                    Spacer(modifier = Modifier.height(24.dp))

                    // 3. Stats Dashboard
                    StatsDashboard(
                        isConnected = isConnected,
                        pingSim = pingSim,
                        speedSim = speedSim,
                        queryCount = queryCount,
                        isPersian = isPersian
                    )
                    Spacer(modifier = Modifier.height(20.dp))

                    // 4. Server Selection Card
                    ServerCard(
                        selectedServer = selectedServer,
                        customDns1 = customDns1,
                        customDns2 = customDns2,
                        isPersian = isPersian,
                        onClick = { showServerSelector = true }
                    )
                    Spacer(modifier = Modifier.height(20.dp))

                    // 5. AI Optimizer Card (Crucial for the user prompt!)
                    AiOptimizerCard(
                        isEnabled = isAiEngineOptimized,
                        isConnected = isConnected,
                        isPersian = isPersian,
                        onToggle = onToggleAiOptimization
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }

            // Global Server Selector Sheet / Dialog
            if (showServerSelector) {
                ServerSelectorDialog(
                    servers = servers,
                    selectedServer = selectedServer,
                    isPersian = isPersian,
                    customDns1 = customDns1,
                    customDns2 = customDns2,
                    onDismiss = { showServerSelector = false },
                    onServerSelected = { server ->
                        onServerSelected(server)
                        showServerSelector = false
                    },
                    onCustomDnsChanged = onCustomDnsChanged
                )
            }
        }
    }
}

@Composable
fun ConnectionCircle(
    isConnected: Boolean,
    onClick: () -> Unit,
    isPersian: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_trans")
    
    // Breathing outer glow effect
    val pulseGlowRadius by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 24f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_radius"
    )

    // Breathing scale for active state
    val buttonScale by animateFloatAsState(
        targetValue = if (isConnected) 1.05f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "button_scale"
    )

    val innerColor = if (isConnected) Color(0xFF00E5FF) else Color(0xFFFF5252)
    val outerColor = if (isConnected) Color(0x3500E5FF) else Color(0x35FF5252)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(190.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .testTag("connection_button")
    ) {
        // Neon pulse rings
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawCircle(
                        color = outerColor,
                        radius = (size.minDimension / 2) - 8f + if (isConnected) pulseGlowRadius else 4f,
                        style = Stroke(width = 6f)
                    )
                }
        )

        // Inner glowing core
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(140.dp)
                .drawBehind {
                    drawCircle(
                        brush = Brush.verticalGradient(
                            colors = if (isConnected) {
                                listOf(Color(0xFF0097A7), Color(0xFF00E5FF))
                            } else {
                                listOf(Color(0xFFC62828), Color(0xFFFF5252))
                            }
                        )
                    )
                }
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = "Power",
                    tint = Color.White,
                    modifier = Modifier.size(46.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isConnected) {
                        if (isPersian) "قطع اتصال" else "DISCONNECT"
                    } else {
                        if (isPersian) "اتصال" else "CONNECT"
                    },
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Composable
fun StatusBadge(isConnected: Boolean, isPersian: Boolean) {
    val text = if (isConnected) {
        if (isPersian) "اتصال برقرار شد - هوش مصنوعی آزاد" else "CONNECTED - AI BOOST ACTIVE"
    } else {
        if (isPersian) "آماده اتصال - فیلتر شکن غیرفعال" else "READY TO CONNECT - VPN PAUSED"
    }
    
    val color = if (isConnected) Color(0xFF00E5FF) else Color(0xFFFF5252)
    val bgColor = if (isConnected) Color(0x1000E5FF) else Color(0x10FF5252)

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bgColor)
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = text,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        }
    }
}

@Composable
fun StatsDashboard(
    isConnected: Boolean,
    pingSim: Int,
    speedSim: Double,
    queryCount: Int,
    isPersian: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF131824))
            .border(1.dp, Color(0xFF232D42), RoundedCornerShape(20.dp))
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        StatItem(
            icon = Icons.Default.Speed,
            title = if (isPersian) "سرعت" else "SPEED",
            value = if (isConnected) String.format("%.1f MB/s", speedSim) else "0.0 MB/s",
            tint = Color(0xFF00E5FF)
        )
        
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(40.dp)
                .background(Color(0xFF232D42))
        )

        StatItem(
            icon = Icons.Default.Bolt,
            title = if (isPersian) "تاخیر" else "PING",
            value = if (isConnected) "$pingSim ms" else "-- ms",
            tint = Color(0xFFFFB300)
        )

        Box(
            modifier = Modifier
                .width(1.dp)
                .height(40.dp)
                .background(Color(0xFF232D42))
        )

        StatItem(
            icon = Icons.Default.Dns,
            title = if (isPersian) "درخواست‌ها" else "RESOLVED",
            value = if (isConnected) "$queryCount" else "0",
            tint = Color(0xFF66BB6A)
        )
    }
}

@Composable
fun StatItem(
    icon: ImageVector,
    title: String,
    value: String,
    tint: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = title,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Color.Gray
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = value,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

@Composable
fun ServerCard(
    selectedServer: ServerConfig,
    customDns1: String,
    customDns2: String,
    isPersian: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .testTag("server_card"),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131824)),
        border = BorderStroke(1.dp, Color(0xFF232D42))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Server country flag or custom icon
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1E2538))
                ) {
                    Text(
                        text = selectedServer.flag,
                        fontSize = 24.sp
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = if (isPersian) "سرور فعال" else "ACTIVE ROUTE SERVER",
                        fontSize = 11.sp,
                        color = Color.Gray,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isPersian) selectedServer.nameFa else selectedServer.nameEn,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (selectedServer.id == "custom") "DNS: $customDns1 | $customDns2" else "DNS: ${selectedServer.dns1} | ${selectedServer.dns2}",
                        fontSize = 12.sp,
                        color = Color(0xFF00E5FF)
                    )
                }
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Select",
                tint = Color.Gray
            )
        }
    }
}

@Composable
fun AiOptimizerCard(
    isEnabled: Boolean,
    isConnected: Boolean,
    isPersian: Boolean,
    onToggle: (Boolean) -> Unit
) {
    var logs by remember { mutableStateOf(listOf<String>()) }

    // Generates realistic bypass connection logs in real-time
    LaunchedEffect(isConnected, isEnabled) {
        if (isConnected && isEnabled) {
            val domainTemplates = listOf(
                "chatgpt.com" to "ChatGPT web socket connected",
                "api.openai.com" to "OpenAI API endpoint optimized",
                "gemini.google.com" to "Google AI tunnel established",
                "claude.ai" to "Anthropic Cloud link accelerated",
                "midjourney.com" to "Visual generative bypass active",
                "api.perplexity.ai" to "Search research node resolved"
            )
            while (true) {
                delay(3000 + Random.nextLong(1000, 3000))
                val select = domainTemplates.random()
                val timestamp = if (isPersian) "هم‌اکنون" else "JUST NOW"
                val logEntry = if (isPersian) {
                    "✓ هدایت ترافیک ${select.first} -> مسیر پرسرعت هوش مصنوعی"
                } else {
                    "✓ Route ${select.first} -> Directed via AI Tunnel"
                }
                logs = (listOf(logEntry) + logs).take(4)
            }
        } else {
            logs = emptyList()
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .testTag("ai_optimizer_card"),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131824)),
        border = BorderStroke(1.dp, if (isEnabled) Color(0xFF00E5FF).copy(alpha = 0.5f) else Color(0xFF232D42))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header Row with Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isEnabled) Color(0x1500E5FF) else Color(0xFF1E2538))
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = if (isEnabled) Color(0xFF00E5FF) else Color.Gray,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (isPersian) "بهینه‌ساز هوش مصنوعی" else "AI Bypass Optimizer",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            if (isEnabled) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFF00E5FF).copy(alpha = 0.2f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (isPersian) "فعال" else "ACTIVE",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF00E5FF)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isPersian) {
                                "شناسایی و هدایت خودکار ترافیک ChatGPT، جمینای و کلود"
                            } else {
                                "Auto-detect and route ChatGPT, Gemini & Claude traffic"
                            },
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }
                }

                Switch(
                    checked = isEnabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF00E5FF),
                        checkedTrackColor = Color(0xFF00525C)
                    ),
                    modifier = Modifier.testTag("ai_optimizer_switch")
                )
            }

            // Expanded panel showing unblocked targets
            AnimatedVisibility(
                visible = isEnabled,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    Divider(color = Color(0xFF232D42), thickness = 1.dp)
                    Spacer(modifier = Modifier.height(14.dp))
                    
                    Text(
                        text = if (isPersian) "سرویس‌های تحت پوشش پایدار:" else "Supported Global AI Platforms:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    // Platform grid list
                    aiPlatforms.forEach { platform ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = platform.icon,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = platform.name,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = if (isPersian) platform.descriptionFa else platform.descriptionEn,
                                        fontSize = 10.sp,
                                        color = Color.Gray
                                    )
                                }
                            }
                            
                            // Unblock Status Badge
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (isConnected) Color(0xFF00E5FF) else Color(0xFFFFB300))
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isConnected) {
                                        if (isPersian) "بدون محدودیت" else "Bypassed"
                                    } else {
                                        if (isPersian) "آماده‌ی دور زدن" else "Ready"
                                    },
                                    fontSize = 11.sp,
                                    color = if (isConnected) Color(0xFF00E5FF) else Color(0xFFFFB300)
                                )
                            }
                        }
                    }

                    // Simulated live packet routing logs
                    if (isConnected && logs.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Divider(color = Color(0xFF232D42), thickness = 1.dp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (isPersian) "گزارش زنده عبور ترافیک هوش مصنوعی:" else "Live AI Traffic Diagnostics:",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00E5FF)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF0C0E14))
                                .padding(8.dp)
                        ) {
                            Column {
                                logs.forEach { log ->
                                    Text(
                                        text = log,
                                        fontSize = 10.sp,
                                        color = Color(0xFF81C784),
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ServerSelectorDialog(
    servers: List<ServerConfig>,
    selectedServer: ServerConfig,
    isPersian: Boolean,
    customDns1: String,
    customDns2: String,
    onDismiss: () -> Unit,
    onServerSelected: (ServerConfig) -> Unit,
    onCustomDnsChanged: (String, String) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(horizontal = 4.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF131824),
            border = BorderStroke(1.dp, Color(0xFF232D42))
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
            ) {
                Text(
                    text = if (isPersian) "انتخاب سرور ضد فیلتر" else "Choose Bypass Server",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 14.dp)
                )

                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(servers) { server ->
                        val isSelected = server.id == selectedServer.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (isSelected) Color(0x1500E5FF) else Color(0xFF1E2538))
                                .border(
                                    1.dp,
                                    if (isSelected) Color(0xFF00E5FF) else Color.Transparent,
                                    RoundedCornerShape(14.dp)
                                )
                                .clickable { onServerSelected(server) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = server.flag, fontSize = 22.sp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = if (isPersian) server.nameFa else server.nameEn,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "IP: ${server.dns1} | ${server.dns2}",
                                        fontSize = 11.sp,
                                        color = Color.Gray
                                    )
                                }
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Bolt,
                                        contentDescription = null,
                                        tint = if (server.basePing < 40) Color(0xFF4CAF50) else Color(0xFFFFA726),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text(
                                        text = "${server.basePing} ms",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (server.basePing < 40) Color(0xFF4CAF50) else Color(0xFFFFA726)
                                    )
                                }
                                if (server.isAiOptimized) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color(0xFF00E5FF).copy(alpha = 0.2f))
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = if (isPersian) "ویژه هوش مصنوعی" else "AI OPTIMIZED",
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF00E5FF)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Custom DNS configuration block
                if (selectedServer.id == "custom") {
                    Spacer(modifier = Modifier.height(16.dp))
                    Divider(color = Color(0xFF232D42), thickness = 1.dp)
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Text(
                        text = if (isPersian) "تنظیم دی‌ان‌اس دستی ضد تحریم:" else "Manual Anti-Filtering DNS Setup:",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00E5FF)
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    var txtDns1 by remember { mutableStateOf(customDns1) }
                    var txtDns2 by remember { mutableStateOf(customDns2) }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = txtDns1,
                            onValueChange = {
                                txtDns1 = it
                                onCustomDnsChanged(it, txtDns2)
                            },
                            label = { Text("Primary DNS", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("dns1_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF00E5FF),
                                unfocusedBorderColor = Color(0xFF232D42)
                            )
                        )

                        OutlinedTextField(
                            value = txtDns2,
                            onValueChange = {
                                txtDns2 = it
                                onCustomDnsChanged(txtDns1, it)
                            },
                            label = { Text("Secondary DNS", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("dns2_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF00E5FF),
                                unfocusedBorderColor = Color(0xFF232D42)
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("close_server_selector"),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00D5E6)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (isPersian) "تایید و بستن" else "Confirm & Close",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
