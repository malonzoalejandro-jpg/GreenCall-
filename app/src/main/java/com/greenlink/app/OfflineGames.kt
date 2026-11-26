package com.greenlink.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

private data class OfflineGame(val name: String, val category: String, val description: String, val symbol: String)

private val offlineGames = listOf(
    OfflineGame("Tic-Tac-Toe", "Puzzle", "Get three marks in a row against the device.", "✕"),
    OfflineGame("Rock Paper Scissors", "Arcade", "Pick your move and beat the computer.", "✊"),
    OfflineGame("Number Guess", "Brain", "Find the secret number from 1 to 100.", "🔢"),
    OfflineGame("Math Sprint", "Brain", "Solve quick arithmetic questions.", "➗"),
    OfflineGame("Memory Match", "Puzzle", "Find matching pairs of symbols.", "🧠"),
    OfflineGame("Tap Rush", "Arcade", "Reach 25 taps before the timer runs out.", "⚡"),
    OfflineGame("Coin Toss", "Casual", "Call heads or tails and test your luck.", "🪙"),
    OfflineGame("Dice Duel", "Casual", "Roll higher than the computer.", "🎲"),
    OfflineGame("Higher or Lower", "Card", "Guess whether the next number is higher.", "⬆"),
    OfflineGame("Word Scramble", "Word", "Unscramble the hidden word.", "🔤"),
    OfflineGame("Hangman", "Word", "Guess the hidden word one letter at a time.", "📝"),
    OfflineGame("Color Match", "Arcade", "Choose the color that matches the prompt.", "🎨"),
    OfflineGame("Reaction Test", "Arcade", "Wait for green, then tap as fast as you can.", "⏱"),
    OfflineGame("Sequence Memory", "Memory", "Remember the number sequence and repeat it.", "🔁"),
    OfflineGame("Lights Out", "Puzzle", "Turn off every light on the board.", "💡"),
    OfflineGame("2048 Mini", "Puzzle", "Combine equal tiles to reach 2048.", "🔢"),
    OfflineGame("Connect Four", "Strategy", "Connect four of your discs before the device.", "🔴"),
    OfflineGame("Minesweeper Mini", "Puzzle", "Reveal safe tiles and avoid hidden mines.", "💣"),
    OfflineGame("Mini Sudoku", "Brain", "Complete the 4 by 4 number grid.", "🔲"),
    OfflineGame("Whack-a-Mole", "Arcade", "Hit the target before it moves.", "🐹")
)

@Composable
fun OfflineGamesScreen(pad: PaddingValues) {
    var selected by remember { mutableIntStateOf(-1) }
    Column(Modifier.fillMaxSize().padding(pad).background(Color(0xFFF5F8F5))) {
        if (selected < 0) {
            Column(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text("Offline Arcade", color = Color(0xFF123D2B), fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
                Text("20 mini games · No internet required", color = Color(0xFF718278), fontSize = 12.sp)
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(offlineGames) { index, game ->
                    Card(
                        modifier = Modifier.fillMaxWidth().height(132.dp).clickable { selected = index },
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(17.dp)
                    ) {
                        Column(Modifier.fillMaxSize().padding(13.dp), verticalArrangement = Arrangement.SpaceBetween) {
                            Text(game.symbol, fontSize = 27.sp)
                            Column {
                                Text(game.name, color = Color(0xFF123D2B), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(game.category.uppercase(), color = Color(0xFF35C982), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        } else {
            GamePlay(index = selected, back = { selected = -1 })
        }
    }
}

@Composable
private fun GamePlay(index: Int, back: () -> Unit) {
    val game = offlineGames[index]
    var score by remember(index) { mutableIntStateOf(0) }
    var mode by remember(index) { mutableStateOf("Solo") }
    var playerOneScore by remember(index) { mutableIntStateOf(0) }
    var playerTwoScore by remember(index) { mutableIntStateOf(0) }
    var activePlayer by remember(index) { mutableIntStateOf(1) }
    var attempts by remember(index) { mutableIntStateOf(0) }
    var message by remember(index) { mutableStateOf("Ready when you are.") }
    var input by remember(index) { mutableStateOf("") }
    var target by remember(index) { mutableIntStateOf(Random.nextInt(1, 101)) }
    var mathA by remember(index) { mutableIntStateOf(Random.nextInt(2, 21)) }
    var mathB by remember(index) { mutableIntStateOf(Random.nextInt(2, 13)) }
    var scramble by remember(index) { mutableStateOf("") }
    var tiles by remember(index) { mutableStateOf(List(16) { if (it == 5) 2 else 0 }) }
    var choice by remember(index) { mutableStateOf("") }
    var board by remember(index) { mutableStateOf(List(9) { "" }) }
    var playerTurn by remember(index) { mutableStateOf(true) }
    var memory by remember(index) { mutableStateOf(List(8) { listOf("🍀","⭐","🍋","🌙")[it % 4] }.shuffled()) }
    var revealed by remember(index) { mutableStateOf(emptySet<Int>()) }
    var matched by remember(index) { mutableStateOf(emptySet<Int>()) }
    var tapCount by remember(index) { mutableIntStateOf(0) }
    var seconds by remember(index) { mutableIntStateOf(15) }
    var running by remember(index) { mutableStateOf(false) }
    var lights by remember(index) { mutableStateOf(List(9) { Random.nextBoolean() }) }
    var number by remember(index) { mutableIntStateOf(Random.nextInt(1, 14)) }
    var word by remember(index) { mutableStateOf(listOf("PLANET","GARDEN","POCKET","ORANGE","STREAM").random()) }
    var letterSet by remember(index) { mutableStateOf(emptySet<Char>()) }
    var colorName by remember(index) { mutableStateOf(listOf("RED","BLUE","GREEN","YELLOW").random()) }
    var reactionReady by remember(index) { mutableStateOf(false) }
    var reactionStarted by remember(index) { mutableStateOf(false) }
    var reactionMillis by remember(index) { mutableLongStateOf(0L) }
    var sequence by remember(index) { mutableStateOf(List(4) { Random.nextInt(1, 5) }.joinToString("")) }
    var fourBoard by remember(index) { mutableStateOf(List(42) { 0 }) }
    var mines by remember(index) { mutableStateOf((0 until 25).shuffled().take(4).toSet()) }
    var opened by remember(index) { mutableStateOf(emptySet<Int>()) }
    var mole by remember(index) { mutableIntStateOf(Random.nextInt(0, 9)) }
    var sudoku by remember(index) { mutableStateOf(listOf(1,2,3,4,3,4,1,2,2,1,4,3,4,3,2,1)) }
    var sudokuInput by remember(index) { mutableStateOf(List(16) { "" }) }
    val scope = rememberCoroutineScope()
    val awardPoint: (Int) -> Unit = { points ->
        if (mode == "1v1") {
            if (activePlayer == 1) playerOneScore += points else playerTwoScore += points
            activePlayer = if (activePlayer == 1) 2 else 1
        } else if (mode != "Practice") score += points
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.Default.ArrowBack, "Back", tint = Color(0xFF123D2B)) }
            Column(Modifier.weight(1f)) {
                Text(game.name, color = Color(0xFF123D2B), fontWeight = FontWeight.ExtraBold, fontSize = 21.sp)
                Text("Offline game · Saved locally while you play", color = Color(0xFF718278), fontSize = 11.sp)
            }
            Text("★ $score", color = Color(0xFF123D2B), fontWeight = FontWeight.Bold)
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(game.description, color = Color(0xFF718278), fontSize = 12.sp)
                Text("CHOOSE MODE", color = Color(0xFF718278), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf("Solo", "1v1", "Practice", "Challenge").forEach { option ->
                        val active = mode == option
                        TextButton(onClick = {
                            mode = option; score = 0; playerOneScore = 0; playerTwoScore = 0; activePlayer = 1
                            attempts = 0; input = ""; message = when(option) {
                                "1v1" -> "Pass the phone between Player 1 and Player 2."
                                "Practice" -> "Practice mode · score is not counted."
                                "Challenge" -> "Challenge mode · chase your highest score."
                                else -> "Solo mode · play against the device."
                            }
                            if(index==0) board = List(9) { "" }
                            if(index==16) fourBoard = List(42) { 0 }
                        }, contentPadding = PaddingValues(horizontal=5.dp, vertical=2.dp)) {
                            Text(option, color=if(active) Color.White else Color(0xFF176B45), fontSize=10.sp,
                                modifier=Modifier.background(if(active) Color(0xFF176B45) else Color(0xFFE8F6EE), RoundedCornerShape(10.dp)).padding(horizontal=6.dp, vertical=5.dp))
                        }
                    }
                }
                if(mode=="1v1") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Text("P1  $playerOneScore", color=Color(0xFF176B45), fontWeight=FontWeight.Bold)
                        Text("P2  $playerTwoScore", color=Color(0xFF176B45), fontWeight=FontWeight.Bold)
                    }
                    Text("Player $activePlayer's turn", color=Color(0xFF176B45), fontWeight=FontWeight.Bold)
                } else Text("SCORE  $score", color=Color(0xFF176B45), fontWeight=FontWeight.Bold)
                Text(message, color = Color(0xFF123D2B), fontWeight = FontWeight.SemiBold)
                when (index) {
                    0 -> {
                        Text(if (playerTurn) "Your turn · X" else "Computer · O", fontWeight = FontWeight.Bold)
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            for (row in 0..2) Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                for (col in 0..2) {
                                    val cell = row * 3 + col
                                    Button(onClick = {
                                        if (board[cell].isEmpty()) {
                                            val next = board.toMutableList()
                                            if(mode=="1v1") {
                                                next[cell] = if(activePlayer==1) "X" else "O"
                                                board = next
                                                val won = listOf(listOf(0,1,2),listOf(3,4,5),listOf(6,7,8),listOf(0,3,6),listOf(1,4,7),listOf(2,5,8),listOf(0,4,8),listOf(2,4,6)).any { line -> line.all { next[it].isNotEmpty() && next[it]==next[line[0]] } }
                                                if(won) { if(activePlayer==1) playerOneScore++ else playerTwoScore++; message="Player $activePlayer wins!" }
                                                else if(next.none { it.isEmpty() }) message="Draw! Start a new round."
                                                else { activePlayer=if(activePlayer==1) 2 else 1; message="Player $activePlayer's turn." }
                                            } else {
                                                next[cell] = "X"
                                                val free = next.indices.filter { next[it].isEmpty() }
                                                if (free.isNotEmpty()) next[free.random()] = "O"
                                                board = next
                                                awardPoint(1)
                                                message = if (next.none { it.isEmpty() }) "Board full — start another round!" else "Your move!"
                                            }
                                        }
                                    }, modifier = Modifier.size(76.dp), contentPadding = PaddingValues(0.dp)) { Text(board[cell].ifEmpty { " " }, fontSize = 25.sp) }
                                }
                            }
                        }
                        TextButton(onClick = { board = List(9) { "" }; message = "New board. You are X." }) { Text("New round") }
                    }
                    1 -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Rock","Paper","Scissors").forEach { pick ->
                                Button(onClick = {
                                    if(mode=="1v1") {
                                        if(choice.isBlank()) { choice=pick; activePlayer=2; message="Player 1 locked in. Pass the phone to Player 2." }
                                        else {
                                            val p1=choice; val p2=pick
                                            when {
                                                p1==p2 -> message="Draw! Both picked $p1."
                                                (p1=="Rock" && p2=="Scissors") || (p1=="Paper" && p2=="Rock") || (p1=="Scissors" && p2=="Paper") -> { playerOneScore++; message="Player 1 wins: $p1 beats $p2." }
                                                else -> { playerTwoScore++; message="Player 2 wins: $p2 beats $p1." }
                                            }
                                            choice=""; activePlayer=1
                                        }
                                    } else {
                                        val cpu = listOf("Rock","Paper","Scissors").random()
                                        choice = pick
                                        message = when {
                                            pick == cpu -> "Draw! Device picked $cpu."
                                            (pick == "Rock" && cpu == "Scissors") || (pick == "Paper" && cpu == "Rock") || (pick == "Scissors" && cpu == "Paper") -> { awardPoint(1); "You win! Device picked $cpu." }
                                            else -> "Device wins with $cpu."
                                        }
                                    }
                                }) { Text(pick) }
                            }
                        }
                    }
                    2 -> {
                        Text("Guess 1–100 · Attempts: $attempts", color = Color(0xFF123D2B))
                        OutlinedTextField(input, { input = it.filter(Char::isDigit).take(3) }, label = { Text("Your guess") }, singleLine = true)
                        Button(onClick = {
                            val guess = input.toIntOrNull()
                            if (guess != null) { attempts++; message = when { guess == target -> { awardPoint(5); "Correct! New number generated."; target = Random.nextInt(1,101); attempts = 0; "Correct! +5 points. Try a new number." }; guess < target -> "Too low — try higher."; else -> "Too high — try lower." } }
                        }) { Text("Check guess") }
                    }
                    3 -> {
                        Text("$mathA × $mathB = ?", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        OutlinedTextField(input, { input = it.filter(Char::isDigit) }, label = { Text("Answer") }, singleLine = true)
                        Button(onClick = { if (input.toIntOrNull() == mathA*mathB) { awardPoint(1); message = "Correct! +1"; input = ""; mathA = Random.nextInt(2,21); mathB = Random.nextInt(2,13) } else message = "Not quite. Try another one." }) { Text("Submit") }
                    }
                    4 -> {
                        Text("Find both matching symbols", fontSize = 13.sp)
                        for (row in 0..1) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (col in 0..3) {
                                val cell = row*4+col
                                Button(onClick = {
                                    if (cell !in matched) {
                                        revealed = revealed + cell
                                        if (revealed.size >= 2) {
                                            val pair = revealed.toList().takeLast(2)
                                            if (memory[pair[0]] == memory[pair[1]]) { matched = matched + pair; awardPoint(1) }
                                            else scope.launch { delay(650); revealed = emptySet() }
                                            if (memory[pair[0]] == memory[pair[1]]) revealed = emptySet()
                                        }
                                    }
                                }, modifier = Modifier.size(62.dp), contentPadding = PaddingValues(0.dp)) {
                                    Text(if (cell in matched || cell in revealed) memory[cell] else "?", fontSize = 20.sp)
                                }
                            }
                        }
                        TextButton(onClick = { memory = listOf("🍀","⭐","🍋","🌙","🍀","⭐","🍋","🌙").shuffled(); revealed = emptySet(); matched = emptySet(); score = 0 }) { Text("Restart") }
                    }
                    5 -> {
                        Text("$tapCount / 25 taps", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Button(onClick = { if (!running) { running = true; tapCount = 0; seconds = 15; scope.launch { while (seconds > 0 && running) { delay(1000); seconds-- }; running = false; message = "Time! You tapped $tapCount times." } } else { tapCount++; if (tapCount >= 25) { awardPoint(3); running = false; message = "Target reached! +3 points." } } }, modifier = Modifier.size(150.dp), shape = RoundedCornerShape(80.dp)) { Text(if (running) "TAP!" else "START", fontSize = 25.sp) }
                        Text("$seconds seconds left")
                    }
                    6 -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf("Heads","Tails").forEach { pick -> Button(onClick = { val flip = listOf("Heads","Tails").random(); message = "It landed $flip."; if (pick == flip) awardPoint(1) }) { Text(pick) } }
                    }
                    7 -> Button(onClick = { val you = (1..6).random(); val cpu = (1..6).random(); message = "You rolled $you · Device rolled $cpu"; if (you > cpu) awardPoint(1) }) { Text("Roll dice") }
                    8 -> {
                        Text("Current number: $number", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Higher","Lower").forEach { pick -> Button(onClick = { val next = Random.nextInt(1,14); val won = if (pick=="Higher") next > number else next < number; message = "Next was $next · " + if (won) "Correct!" else "Not this time."; if (won) awardPoint(1); number = next }) { Text(pick) } }
                        }
                    }
                    9 -> {
                        if (scramble.isBlank()) scramble = word.toList().shuffled().joinToString("")
                        Text(scramble, fontSize = 27.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                        OutlinedTextField(input, { input = it.uppercase() }, label = { Text("Unscrambled word") }, singleLine = true)
                        Button(onClick = { if (input.trim() == word) { awardPoint(2); message = "Correct! +2 points."; word = listOf("PLANET","GARDEN","POCKET","ORANGE","STREAM").random(); scramble = ""; input = "" } else message = "Keep trying!" }) { Text("Check word") }
                    }
                    10 -> {
                        Text(word.map { if (it in letterSet) "$it " else "_ " }.joinToString(""), fontSize = 24.sp, letterSpacing = 2.sp)
                        Text("Wrong guesses: ${letterSet.count { it !in word }} / 6")
                        for (row in 0..2) Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            for (col in 0..8) {
                                val ch = ('A'.code + row*9+col).toChar()
                                if (ch <= 'Z') TextButton(onClick = { letterSet = letterSet + ch; if (ch in word) awardPoint(1) }) { Text(ch.toString()) }
                            }
                        }
                        TextButton(onClick = { word = listOf("PLANET","GARDEN","POCKET","ORANGE","STREAM").random(); letterSet = emptySet() }) { Text("New word") }
                    }
                    11 -> {
                        Text("Tap the color: $colorName", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        listOf("RED","BLUE","GREEN","YELLOW").forEach { c -> Button(onClick = { if (c == colorName) { awardPoint(1); message = "Correct!"; colorName = listOf("RED","BLUE","GREEN","YELLOW").random() } else message = "Try again." }) { Text(c) } }
                    }
                    12 -> {
                        Button(onClick = {
                            if (!reactionStarted) {
                                reactionStarted = true; reactionReady = false; message = "Wait for green…"
                                scope.launch { delay(Random.nextLong(1500,4500)); reactionReady = true; reactionMillis = System.currentTimeMillis(); message = "TAP NOW!" }
                            } else if (reactionReady) {
                                val ms = System.currentTimeMillis()-reactionMillis; score += if (ms < 350) 2 else 1
                                message = "Your reaction: $ms ms"; reactionStarted = false; reactionReady = false
                            } else message = "Too early! Try again."
                        }, modifier = Modifier.fillMaxWidth().height(90.dp)) { Text(if (reactionReady) "TAP NOW!" else if (reactionStarted) "WAIT…" else "START TEST", fontSize = 23.sp) }
                    }
                    13 -> {
                        Text(sequence.chunked(1).joinToString("  "), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Text("Memorize it, then type it below.")
                        OutlinedTextField(input, { input = it.filter(Char::isDigit).take(8) }, label = { Text("Sequence") }, singleLine = true)
                        Button(onClick = { if (input == sequence) { awardPoint(3); message = "Perfect memory!"; sequence = List(4) { Random.nextInt(1,5) }.joinToString(""); input = "" } else message = "Not quite. Try again." }) { Text("Check sequence") }
                    }
                    14 -> {
                        for (row in 0..2) Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            for (col in 0..2) {
                                val cell = row*3+col
                                Button(onClick = { lights = lights.mapIndexed { i,v -> if (i==cell || i==cell-1 && cell%3>0 || i==cell+1 && cell%3<2 || i==cell-3 || i==cell+3) !v else v }; if (lights.none { it }) { awardPoint(5); message = "All lights out! +5"; lights = List(9) { Random.nextBoolean() } } }, modifier = Modifier.size(70.dp)) { Text(if (lights[cell]) "💡" else " ", fontSize = 22.sp) }
                            }
                        }
                        Text("Tap a light to toggle it and its neighbors.")
                    }
                    15 -> {
                        Text("Reach 2048 by combining equal tiles.", fontSize = 12.sp)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (row in 0..3) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (col in 0..3) {
                                    val v = tiles[row*4+col]
                                    Box(Modifier.size(58.dp).background(if (v == 0) Color(0xFFE8F6EE) else Color(0xFFB7E4C7), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                                        Text(if (v > 0) v.toString() else "·", color = Color(0xFF123D2B), fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            listOf("↑","↓","←","→").forEach { direction ->
                                Button(onClick = {
                                    val next = move2048(tiles, direction)
                                    if (next != tiles) {
                                        val empty = next.indices.filter { next[it] == 0 }
                                        val spawned = next.toMutableList()
                                        if (empty.isNotEmpty()) spawned[empty.random()] = if (Random.nextInt(10)==0) 4 else 2
                                        tiles = spawned
                                        if (tiles.maxOrNull() == 2048) { awardPoint(20); message = "You reached 2048! Amazing!" }
                                    } else message = "No tiles moved that way."
                                }) { Text(direction) }
                            }
                        }
                        TextButton(onClick = { tiles = List(16) { if (it == 5) 2 else 0 }; message = "New 2048 board." }) { Text("Restart") }
                    }
                    16 -> {
                        Text("You: Red · Device: Yellow")
                        for (row in 0..5) Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            for (col in 0..6) {
                                val cell = row*7+col
                                Box(Modifier.size(35.dp).background(when(fourBoard[cell]) { 1 -> Color(0xFFE34D4D); 2 -> Color(0xFFE6B83F); else -> Color(0xFFE8F6EE) }, RoundedCornerShape(50.dp)).clickable {
                                    val next = fourBoard.toMutableList()
                                    val landing = (5 downTo 0).map { it*7+col }.firstOrNull { next[it]==0 }
                                    if (landing != null && playerTurn) {
                                        next[landing] = if(mode=="1v1" && activePlayer==2) 2 else 1
                                        if(mode!="1v1") {
                                            val freeColumns = (0..6).filter { col -> (0..5).any { row -> next[row*7+col] == 0 } }
                                            if (freeColumns.isNotEmpty()) {
                                                val cpuCol = freeColumns.random()
                                                val cpuLanding = (5 downTo 0).map { it*7+cpuCol }.firstOrNull { next[it] == 0 }
                                                if (cpuLanding != null) next[cpuLanding] = 2
                                            }
                                        } else activePlayer = if(activePlayer==1) 2 else 1
                                        fourBoard = next
                                        awardPoint(1)
                                    }
                                })
                            }
                        }
                        TextButton(onClick = { fourBoard = List(42){0}; playerTurn = true }) { Text("Restart") }
                    }
                    17 -> {
                        Text("Tap tiles. Avoid 4 hidden mines.")
                        for (row in 0..4) Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            for (col in 0..4) {
                                val cell=row*5+col
                                Button(onClick = { opened = opened + cell; if(cell in mines) { message = "Boom! New board."; mines = (0 until 25).shuffled().take(4).toSet(); opened = emptySet() } else { awardPoint(1); if(opened.size>=20) message = "Board cleared!" } }, modifier = Modifier.size(47.dp), contentPadding = PaddingValues(0.dp)) { Text(if(cell in opened) if(cell in mines) "💣" else "✓" else "?") }
                            }
                        }
                    }
                    18 -> {
                        Text("Fill each blank with the missing number.")
                        for (row in 0..3) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (col in 0..3) {
                                val cell=row*4+col
                                if (cell in listOf(2,7,8,13)) {
                                    OutlinedTextField(sudokuInput[cell], { v -> sudokuInput = sudokuInput.toMutableList().also { it[cell]=v.filter(Char::isDigit).take(1) } }, modifier=Modifier.width(62.dp), singleLine=true)
                                } else Box(Modifier.size(62.dp).background(Color(0xFFE8F6EE), RoundedCornerShape(8.dp)), contentAlignment=Alignment.Center) { Text(sudoku[cell].toString(), fontWeight=FontWeight.Bold) }
                            }
                        }
                        Button(onClick = { val ok = listOf(2,7,8,13).all { sudokuInput[it].toIntOrNull()==sudoku[it] }; if(ok) { score+=5; message="Solved! +5 points." } else message="Check the missing cells." }) { Text("Check puzzle") }
                    }
                    19 -> {
                        Text("Find the green target!")
                        for (row in 0..2) Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            for (col in 0..2) {
                                val cell=row*3+col
                                Button(onClick = { if(cell==mole) { awardPoint(1); message="Nice hit!"; mole=Random.nextInt(0,9) } else message="Missed — try again." }, modifier=Modifier.size(72.dp)) { Text(if(cell==mole) "🐹" else "·", fontSize=22.sp) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun move2048(source: List<Int>, direction: String): List<Int> {
    val board = source.toMutableList()
    fun merge(line: List<Int>): List<Int> {
        val packed = line.filter { it != 0 }
        val result = mutableListOf<Int>()
        var i = 0
        while (i < packed.size) {
            if (i + 1 < packed.size && packed[i] == packed[i + 1]) {
                result.add(packed[i] * 2)
                i += 2
            } else {
                result.add(packed[i])
                i++
            }
        }
        while (result.size < 4) result.add(0)
        return result
    }
    for (i in 0..3) {
        val indices = when (direction) {
            "←" -> (0..3).map { i*4+it }
            "→" -> (3 downTo 0).map { i*4+it }
            "↑" -> (0..3).map { it*4+i }
            else -> (3 downTo 0).map { it*4+i }
        }
        val moved = merge(indices.map { board[it] })
        indices.forEachIndexed { j, cell -> board[cell] = moved[j] }
    }
    return board
}
