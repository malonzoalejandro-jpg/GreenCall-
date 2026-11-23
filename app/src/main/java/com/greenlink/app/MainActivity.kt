package com.greenlink.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

private val Forest = Color(0xFF0D3B2A)
private val Leaf = Color(0xFF28C783)
private val Mint = Color(0xFFE8F7EF)
private val Ink = Color(0xFF10251B)
private val Muted = Color(0xFF718278)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GreenCallApp() }
    }
}

@Composable
private fun GreenCallApp() {
    var tab by remember { mutableStateOf(0) }
    var muted by remember { mutableStateOf(false) }
    var ptt by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(false) }
    val chats = remember {
        mutableStateListOf(
            Chat("Mika Santos", "Are you nearby?", "2m", false, "M"),
            Chat("Weekend Squad", "Leo: voice message · 0:08", "12m", true, "W"),
            Chat("Andre", "I'll send it when I get signal", "1h", false, "A")
        )
    }
    MaterialTheme(
        colorScheme = lightColorScheme(primary = Forest, secondary = Leaf, background = Color(0xFFF6FAF7))
    ) {
        Scaffold(
            containerColor = Color(0xFFF6FAF7),
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                        icon = { Icon(Icons.Default.ChatBubble, null) }, label = { Text("Chats") })
                    NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                        icon = { Icon(Icons.Default.Groups, null) }, label = { Text("Groups") })
                    NavigationBarItem(selected = tab == 2, onClick = { tab = 2 },
                        icon = { Icon(Icons.Default.People, null) }, label = { Text("Friends") })
                    NavigationBarItem(selected = tab == 3, onClick = { tab = 3 },
                        icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
                }
            },
            floatingActionButton = {
                if (tab != 3) FloatingActionButton(onClick = { showNew = true }, containerColor = Leaf, contentColor = Forest) {
                    Icon(Icons.Default.Add, "New chat")
                }
            }
        ) { padding ->
            when (tab) {
                0 -> ChatsScreen(chats, muted, { muted = it }, ptt, { ptt = it }, Modifier.padding(padding))
                1 -> GroupsScreen(Modifier.padding(padding))
                2 -> FriendsScreen(Modifier.padding(padding))
                else -> SettingsScreen(muted, { muted = it }, Modifier.padding(padding))
            }
        }
        if (showNew) AlertDialog(
            onDismissRequest = { showNew = false },
            title = { Text("Start a conversation") },
            text = { Text("Friend search and group creation will connect to your GreenCall account once the backend is configured.") },
            confirmButton = { TextButton(onClick = { showNew = false }) { Text("Got it") } }
        )
    }
}

private data class Chat(val name: String, val message: String, val time: String, val group: Boolean, val initial: String)

@Composable
private fun Header(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 22.dp, vertical = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).background(Mint, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Waves, null, tint = Forest)
            }
            Spacer(Modifier.width(10.dp))
            Text("GreenCall", color = Forest, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(9.dp).background(Leaf, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text("LOCAL MODE", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
        Spacer(Modifier.height(22.dp))
        Text(title, color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun ChatsScreen(chats: List<Chat>, muted: Boolean, onMuted: (Boolean) -> Unit,
                        ptt: Boolean, onPtt: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        Header("Your conversations", "Messages stay queued until a route is available.")
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusChip("Offline-ready", Icons.Default.WifiOff)
            StatusChip("Encrypted design", Icons.Default.Lock)
        }
        Card(Modifier.fillMaxWidth().padding(horizontal = 18.dp), colors = CardDefaults.cardColors(containerColor = Forest),
            shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Walkie-talkie", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(if (muted) "Muted · incoming clips are saved" else "Unmuted · live audio when connected",
                            color = Color(0xFFBDE8D0), fontSize = 12.sp)
                    }
                    Switch(checked = muted, onCheckedChange = onMuted,
                        colors = SwitchDefaults.colors(checkedThumbColor = Forest, checkedTrackColor = Leaf))
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = { onPtt(!ptt) }, modifier = Modifier.fillMaxWidth().height(54.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (ptt) Color(0xFFFFD9D2) else Leaf),
                    shape = RoundedCornerShape(16.dp)) {
                    Icon(if (ptt) Icons.Default.Stop else Icons.Default.Mic, null,
                        tint = if (ptt) Color(0xFF9D2C1D) else Forest)
                    Spacer(Modifier.width(9.dp))
                    Text(if (ptt) "STOP TRANSMITTING" else "TAP TO TALK", color = if (ptt) Color(0xFF9D2C1D) else Forest,
                        fontWeight = FontWeight.ExtraBold)
                }
                Text("Prototype control · microphone transport is not connected yet",
                    color = Color(0xFFBDE8D0), fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
        Text("RECENT", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp, modifier = Modifier.padding(start = 22.dp, top = 22.dp, bottom = 8.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 18.dp)) {
            items(chats) { chat ->
                Row(Modifier.fillMaxWidth().clickable { }.padding(horizontal = 20.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).background(Mint, CircleShape), contentAlignment = Alignment.Center) {
                        Text(chat.initial, color = Forest, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text(chat.name, color = Ink, fontWeight = FontWeight.SemiBold)
                        Text(chat.message, color = Muted, fontSize = 12.sp, maxLines = 1)
                    }
                    Text(chat.time, color = Muted, fontSize = 11.sp)
                }
                HorizontalDivider(color = Color(0xFFEAF0EC), modifier = Modifier.padding(start = 80.dp))
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(Modifier.background(Mint, RoundedCornerShape(50)).padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Forest, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Forest, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GroupsScreen(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        Header("Your groups", "One space for messages and group voice.")
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FeatureCard("Weekend Squad", "6 members · group chat + push-to-talk", Icons.Default.Groups)
            FeatureCard("Family", "4 members · offline message queue", Icons.Default.FamilyRestroom)
            Text("Create a group to invite friends and choose who can transmit.", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun FriendsScreen(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        Header("Friends", "People you can message or call.")
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FeatureCard("Add a friend", "Search by username or invite link", Icons.Default.PersonAdd)
            FeatureCard("Friend requests", "Requests will appear here", Icons.Default.MarkEmailUnread)
            Text("Account sign-in and friend syncing require the server setup.", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SettingsScreen(muted: Boolean, onMuted: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        Header("Settings", "Control how GreenCall reaches you.")
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Mute walkie-talkie", color = Ink, fontWeight = FontWeight.SemiBold)
                        Text("Save incoming transmissions as voice mail instead of playing live.",
                            color = Muted, fontSize = 12.sp)
                    }
                    Switch(checked = muted, onCheckedChange = onMuted)
                }
            }
            FeatureCard("Offline delivery", "Nearby devices and opted-in relays can forward queued messages.", Icons.Default.Hub)
            FeatureCard("Privacy", "Relay devices should forward encrypted packets only.", Icons.Default.Shield)
            FeatureCard("Account", "Registration, login and cloud sync are backend milestones.", Icons.Default.AccountCircle)
        }
    }
}

@Composable
private fun FeatureCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(Mint, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Forest)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, color = Ink, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = Muted, fontSize = 12.sp)
            }
        }
    }
}
