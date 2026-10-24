package com.papertrail.notebook

import android.graphics.Paint as NativePaint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

private val Ink = Color(0xFF25332C)
private val Moss = Color(0xFF496B55)
private val Paper = Color(0xFFF7F4ED)
private val Muted = Color(0xFF888C83)
private data class StrokeData(val points: List<Offset>, val color: Color, val width: Float, val kind: String = "pen", val pressures: List<Float> = emptyList())
private data class NoteText(val text: String, val x: Float, val y: Float, val color: Color, val size: Float)
private data class Book(val id: Long, val title: String, val color: Long, val updated: String, val favorite: Boolean = false)
private enum class Tool { PEN, HIGHLIGHTER, ERASER, TEXT, LASSO, LINE, RECTANGLE, CIRCLE }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(247,244,237)
        window.navigationBarColor = android.graphics.Color.rgb(247,244,237)
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContent { PapertrailApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PapertrailApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("papertrail", 0) }
    var books by remember { mutableStateOf(loadBooks(prefs)) }
    var selected by remember { mutableStateOf<Book?>(null) }
    var search by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }
    var showTemplates by remember { mutableStateOf(false) }
    var showBookMenu by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var pageStyle by remember { mutableIntStateOf(prefs.getInt("pageStyle", 0)) }
    var page by remember { mutableIntStateOf(1) }
    var tool by remember { mutableStateOf(Tool.PEN) }
    var color by remember { mutableStateOf(Color(0xFF243C32)) }
    var width by remember { mutableFloatStateOf(3.5f) }
    var stabilization by remember { mutableFloatStateOf(prefs.getFloat("stabilization", .22f)) }
    var stylusOnly by remember { mutableStateOf(prefs.getBoolean("stylusOnly", false)) }
    var showPenSettings by remember { mutableStateOf(false) }
    var dark by remember { mutableStateOf(false) }
    var pageCount by remember(selected) { mutableIntStateOf(selected?.let { prefs.getInt("count_${it.id}", 1) } ?: 1) }
    var strokes by remember(selected?.id, page) { mutableStateOf(loadStrokes(prefs, selected?.id ?: -1, page)) }
    var notes by remember(selected?.id, page) { mutableStateOf(loadNotes(prefs, selected?.id ?: -1, page)) }
    var redo by remember(selected?.id, page) { mutableStateOf<List<StrokeData>>(emptyList()) }
    var draft by remember { mutableStateOf<StrokeData?>(null) }
    var lastSampleTime by remember { mutableLongStateOf(0L) }
    var selection by remember { mutableStateOf<Rect?>(null) }
    var selectedIndexes by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var textDialog by remember { mutableStateOf(false) }
    var textDraft by remember { mutableStateOf("") }
    var textPosition by remember { mutableStateOf(Offset(120f, 160f)) }
    val bg = if (dark) Color(0xFF202722) else Paper
    val ink = if (dark) Color(0xFFE9E6DD) else Ink

    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> if (uri != null && selected != null) exportPdfToUri(context, uri, selected!!, pageCount, pageStyle) }
    fun persistBooks(next: List<Book>) { books = next; prefs.edit().putString("books", booksToJson(next)).apply() }
    fun persistPage() { selected?.let { saveStrokes(prefs, it.id, page, strokes); saveNotes(prefs, it.id, page, notes) } }
    fun addPage() { persistPage(); pageCount++; selected?.let { prefs.edit().putInt("count_${it.id}", pageCount).apply() }; page = pageCount; strokes = emptyList(); notes = emptyList(); redo = emptyList(); selection = null }

    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFFA8C8A7), background = bg, surface = Color(0xFF2A332D)) else lightColorScheme(primary = Moss, background = bg, surface = Color.White)) {
        if (selected == null) {
            Row(Modifier.fillMaxSize().background(bg)) {
                Column(Modifier.width(220.dp).fillMaxHeight().background(if (dark) Color(0xFF252D28) else Color(0xFFECE9E0)).padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(36.dp).background(Moss, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.EditNote, null, tint = Color.White) }; Spacer(Modifier.width(11.dp)); Text("papertrail", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = ink) }
                    Spacer(Modifier.height(52.dp)); NavItem("⌂", "Kitaplığım", true, ink)
                    NavItem("☆", "Favoriler", false, ink) { search = "#favorite" }
                    Spacer(Modifier.weight(1f)); Text("Sakin bir alan, berrak fikirler.", color = Muted, fontSize = 12.sp)
                }
                Column(Modifier.fillMaxSize().padding(horizontal = 42.dp, vertical = 32.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("İyi günler ☀", color = Moss, fontSize = 14.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(8.dp)); Text("Notların, senin alanın.", color = ink, fontSize = 30.sp, fontWeight = FontWeight.Bold) }
                        OutlinedButton(onClick = { dark = !dark }, shape = RoundedCornerShape(14.dp)) { Icon(if (dark) Icons.Default.LightMode else Icons.Default.DarkMode, null); Spacer(Modifier.width(8.dp)); Text("Görünüm") }
                        Spacer(Modifier.width(12.dp)); Button(onClick = { newTitle = ""; dialog = true }, shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Yeni defter") }
                    }
                    Spacer(Modifier.height(26.dp))
                    OutlinedTextField(value = search.removePrefix("#favorite"), onValueChange = { search = it }, placeholder = { Text("Defterlerinde ara…") }, leadingIcon = { Icon(Icons.Default.Search, null, tint = Muted) }, modifier = Modifier.fillMaxWidth(0.63f), shape = RoundedCornerShape(16.dp), singleLine = true)
                    Spacer(Modifier.height(28.dp)); Row(verticalAlignment = Alignment.CenterVertically) { Text(if (search == "#favorite") "Favori defterlerin" else "Defter rafın", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = ink); Spacer(Modifier.width(12.dp)); Text("${books.size} defter", color = Muted, fontSize = 13.sp) }
                    Spacer(Modifier.height(18.dp))
                    val filtered = books.filter { book -> search == "#favorite" && book.favorite || search != "#favorite" && (book.title.contains(search, true) || (search.isNotBlank() && (1..prefs.getInt("count_${book.id}", 1)).any { p -> loadNotes(prefs, book.id, p).any { it.text.contains(search, true) } })) }
                    if (filtered.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("✦", fontSize = 38.sp, color = Moss); Spacer(Modifier.height(8.dp)); Text(if (search.isBlank()) "İlk defterini oluştur" else "Eşleşen defter bulunamadı", color = Muted); Spacer(Modifier.height(14.dp)); TextButton(onClick = { newTitle = ""; dialog = true }) { Text("+  Yeni defter oluştur") } } }
                    else LazyColumn(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                        items(filtered.chunked(4)) { rowBooks -> Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) { rowBooks.forEach { book -> BookCard(book, onOpen = { val count=prefs.getInt("count_${book.id}",1); val target=if(search.isNotBlank()&&search!="#favorite"&&!book.title.contains(search,true)) (1..count).firstOrNull{p->loadNotes(prefs,book.id,p).any{it.text.contains(search,true)}}?:1 else 1; selected=book; page=target; pageCount=count }, onFavorite = { b -> persistBooks(books.map { if (it.id == b.id) it.copy(favorite = !it.favorite) else it }) }, onDelete = { b -> persistBooks(books.filterNot { it.id == b.id }); prefs.edit().remove("count_${b.id}").apply() }, onRename = { b -> selected = b; newTitle = b.title; renameDialog = true }) } } }
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().background(bg)) {
                Row(Modifier.fillMaxWidth().height(66.dp).background(if (dark) Color(0xFF252D28) else Color.White).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { persistPage(); selected = null }) { Icon(Icons.Default.ArrowBack, "Kitaplığa dön", tint = ink) }
                    Column(Modifier.widthIn(max = 230.dp)) { Text(selected!!.title, color = ink, fontWeight = FontWeight.Bold, fontSize = 16.sp); Text("Otomatik kaydediliyor · Sayfa $page", color = Muted, fontSize = 11.sp) }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { if (strokes.isNotEmpty()) { redo = redo + strokes.last(); strokes = strokes.dropLast(1); persistPage() } }) { Icon(Icons.Default.Undo, "Geri al", tint = ink) }
                    IconButton(onClick = { if (redo.isNotEmpty()) { strokes = strokes + redo.last(); redo = redo.dropLast(1); persistPage() } }) { Icon(Icons.Default.Redo, "Yinele", tint = ink) }
                    IconButton(onClick = { showTemplates = true }) { Icon(Icons.Default.GridView, "Şablon", tint = ink) }
                    Box { IconButton(onClick = { showBookMenu = true }) { Icon(Icons.Default.MoreVert, "Defter seçenekleri", tint = ink) }; DropdownMenu(showBookMenu, { showBookMenu = false }) { DropdownMenuItem(text = { Text("Kalem motoru ayarları") }, leadingIcon = { Icon(Icons.Default.Tune, null) }, onClick = { showPenSettings = true; showBookMenu = false }); DropdownMenuItem(text = { Text("Bu sayfayı temizle") }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) }, onClick = { strokes = emptyList(); notes = emptyList(); persistPage(); showBookMenu = false }); DropdownMenuItem(text = { Text("PDF olarak dışa aktar") }, leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) }, onClick = { pdfLauncher.launch("${selected!!.title.replace(Regex("[^A-Za-z0-9_-]"), "_")}.pdf"); showBookMenu = false }); DropdownMenuItem(text = { Text("Defter adını değiştir") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { newTitle = selected!!.title; showBookMenu = false; renameDialog = true }) } }
                }
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.width(76.dp).fillMaxHeight().background(if (dark) Color(0xFF252D28) else Color.White), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        ToolButton(Icons.Default.Edit, "Kalem", tool == Tool.PEN) { tool = Tool.PEN }
                        ToolButton(Icons.Default.Brush, "Vurgula", tool == Tool.HIGHLIGHTER) { tool = Tool.HIGHLIGHTER }
                        ToolButton(Icons.Default.AutoFixNormal, "Silgi", tool == Tool.ERASER) { tool = Tool.ERASER }
                        ToolButton(Icons.Default.TextFields, "Metin", tool == Tool.TEXT) { tool = Tool.TEXT }
                        ToolButton(Icons.Default.Gesture, "Kement", tool == Tool.LASSO) { tool = Tool.LASSO }
                        ToolButton(Icons.Default.ShowChart, "Çizgi", tool == Tool.LINE) { tool = Tool.LINE }
                        ToolButton(Icons.Default.Rectangle, "Dikdörtgen", tool == Tool.RECTANGLE) { tool = Tool.RECTANGLE }
                        ToolButton(Icons.Default.Circle, "Daire", tool == Tool.CIRCLE) { tool = Tool.CIRCLE }
                        HorizontalDivider(Modifier.padding(horizontal = 13.dp, vertical = 4.dp))
                        listOf(Color(0xFF243C32), Color(0xFF334D78), Color(0xFFB44B48), Color(0xFFE5AA48), Color(0xFF242424), Color(0xFF8D5DB7)).forEach { c -> Box(Modifier.padding(3.dp).size(23.dp).background(c, CircleShape).then(if (c == color) Modifier.border(2.dp, Moss, CircleShape) else Modifier).clickable { color = c }) }
                        Text("Uç", fontSize = 10.sp, color = Muted); Slider(value = width, onValueChange = { width = it }, valueRange = 1f..12f, modifier = Modifier.height(42.dp).width(70.dp))
                        Spacer(Modifier.weight(1f)); if (selectedIndexes.isNotEmpty()) { IconButton(onClick = { strokes = strokes.filterIndexed { i, _ -> i !in selectedIndexes }; selectedIndexes = emptySet(); selection = null; persistPage() }) { Icon(Icons.Default.Delete, "Seçimi sil", tint = Color.Red) }; IconButton(onClick = { val copies = selectedIndexes.mapNotNull { strokes.getOrNull(it) }.map { s -> s.copy(points = s.points.map { Offset(it.x + 24, it.y + 24) }) }; strokes += copies; selectedIndexes = emptySet(); selection = null; persistPage() }) { Icon(Icons.Default.ContentCopy, "Seçimi kopyala", tint = Moss) } }
                    }
                    val preparedStrokes = remember(strokes) { strokes.map(::prepareStroke) }
                    val preparedDraft = remember(draft) { draft?.takeIf { it.kind != "lasso" && it.kind != "move" }?.let(::prepareStroke) }
                    Box(Modifier.weight(1f).fillMaxHeight().background(if (dark) Color(0xFF313833) else Color(0xFFE8E6E0)), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 18.dp).graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = pan.x, translationY = pan.y)
                            .pointerInput(tool, color, width, page, strokes, stabilization, stylusOnly) {
                                detectDragGestures(onDragStart = { p ->
                                    lastSampleTime = 0L
                                    if (tool == Tool.LASSO) draft = StrokeData(listOf(p), Moss, 1.6f, if (selection?.contains(p) == true) "move" else "lasso")
                                    else if (tool != Tool.TEXT) draft = StrokeData(listOf(p), if (tool == Tool.ERASER) { if (dark) Color(0xFF2C332E) else Color(0xFFFFFEFA) } else if (tool == Tool.HIGHLIGHTER) color.copy(alpha = .32f) else color, if (tool == Tool.HIGHLIGHTER) width * 4 else width, when (tool) { Tool.HIGHLIGHTER -> "highlight"; Tool.ERASER -> "erase"; Tool.LINE -> "line"; Tool.RECTANGLE -> "rect"; Tool.CIRCLE -> "circle"; else -> "pen" }, listOf(.52f))
                                }, onDrag = { change, _ -> if (!stylusOnly || change.type == PointerType.Stylus || change.type == PointerType.Eraser) { change.consume(); val active = draft; if (active != null) { val previous = active.points.last(); val smoothing = if (tool == Tool.PEN || tool == Tool.HIGHLIGHTER) stabilization else 0f; val dt = if (lastSampleTime == 0L) 16f else (change.uptimeMillis - lastSampleTime).coerceAtLeast(1L).toFloat(); val dx = change.position.x - previous.x; val dy = change.position.y - previous.y; val speed = kotlin.math.sqrt(dx*dx + dy*dy) / dt; val nextPoint = InkEngine.stabilize(previous, change.position, smoothing, speed); lastSampleTime = change.uptimeMillis; draft = active.copy(points = active.points + nextPoint, pressures = active.pressures + change.pressure.coerceIn(.08f, 1f)) } } else draft = null }, onDragEnd = {
                                    draft?.let { s -> if (s.kind == "move" && s.points.size > 1) { val dx=s.points.last().x-s.points.first().x; val dy=s.points.last().y-s.points.first().y; strokes=strokes.mapIndexed { i, stroke -> if(i in selectedIndexes) stroke.copy(points=stroke.points.map { Offset(it.x+dx,it.y+dy) }) else stroke }; selection=selection?.let { Rect(it.left+dx,it.top+dy,it.right+dx,it.bottom+dy) }; persistPage() } else if (s.kind == "lasso") { val xs=s.points.map { it.x }; val ys=s.points.map { it.y }; val r=Rect(xs.minOrNull() ?: 0f, ys.minOrNull() ?: 0f, xs.maxOrNull() ?: 0f, ys.maxOrNull() ?: 0f); selection=r; selectedIndexes=strokes.indices.filter { i -> strokes[i].points.any { r.contains(it) } }.toSet() } else { strokes = strokes + s; redo = emptyList(); persistPage() } }; draft = null
                                })
                            }
                            .pointerInput(tool) { detectTapGestures { p -> if (tool == Tool.TEXT) { textPosition = p; textDraft = ""; textDialog = true } } }
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    var event = awaitPointerEvent()
                                    while (event.changes.count { it.pressed } < 2) {
                                        if (event.changes.none { it.pressed }) return@awaitEachGesture
                                        event = awaitPointerEvent()
                                    }
                                    fun center(points: List<Offset>) = Offset(points.map { it.x }.average().toFloat(), points.map { it.y }.average().toFloat())
                                    fun spread(points: List<Offset>, c: Offset): Float = points.map { p -> kotlin.math.sqrt((p.x-c.x)*(p.x-c.x)+(p.y-c.y)*(p.y-c.y)) }.average().toFloat()
                                    var oldPoints = event.changes.filter { it.pressed }.map { it.position }
                                    while (event.changes.count { it.pressed } >= 2) {
                                        val active = event.changes.filter { it.pressed }
                                        val newPoints = active.map { it.position }
                                        if (oldPoints.size >= 2 && newPoints.size >= 2) {
                                            val oldCenter = center(oldPoints); val newCenter = center(newPoints)
                                            val oldSpread = spread(oldPoints, oldCenter); val newSpread = spread(newPoints, newCenter)
                                            pan += newCenter - oldCenter
                                            if (oldSpread > 1f) zoom = (zoom * (newSpread / oldSpread)).coerceIn(.55f, 3f)
                                            active.forEach { it.consume() }
                                        }
                                        oldPoints = newPoints
                                        event = awaitPointerEvent()
                                    }
                                }
                            }) {
                            drawRect(if (dark) Color(0xFF2C332E) else Color(0xFFFFFEFA))
                            val step = 34.dp.toPx()
                            when (pageStyle) { 0 -> { var y=step; while(y<size.height){drawLine(if(dark)Color(0xFF3D463F) else Color(0xFFE6E4DC),Offset(0f,y),Offset(size.width,y),1f);y+=step} }; 1 -> { var x=step; var y=step; while(y<size.height){drawLine(Color(0xFFE8E6DE),Offset(0f,y),Offset(size.width,y),1f);y+=step}; while(x<size.width){drawLine(Color(0xFFE8E6DE),Offset(x,0f),Offset(x,size.height),1f);x+=step} }; 2 -> { var y=step; while(y<size.height){drawLine(Color(0xFFE6E4DC),Offset(0f,y),Offset(size.width,y),1f);y+=step}; drawLine(Color(0xFFE9B7B0),Offset(step*2,0f),Offset(step*2,size.height),1.2f) } }
                            preparedStrokes.forEachIndexed { i, rendered -> drawRenderedStroke(rendered); if(i in selectedIndexes) { val b=rendered.stroke.bounds(); drawRect(Moss.copy(alpha=.35f),Offset(b.left,b.top),androidx.compose.ui.geometry.Size(b.width,b.height),style=Stroke(2f)) } }
                            preparedDraft?.let(::drawRenderedStroke)
                            selection?.let { drawRect(Moss.copy(alpha=.7f),Offset(it.left,it.top),androidx.compose.ui.geometry.Size(it.width,it.height),style=Stroke(2f)) }
                            notes.forEach { note -> drawContext.canvas.nativeCanvas.drawText(note.text, note.x, note.y, NativePaint().apply { color=note.color.toArgb(); textSize=note.size; isAntiAlias=true }) }
                        }
                    }
                    Column(Modifier.width(158.dp).fillMaxHeight().background(if (dark) Color(0xFF252D28) else Color.White).padding(12.dp)) {
                        Text("SAYFALAR", fontSize=11.sp, color=Muted, fontWeight=FontWeight.Bold, letterSpacing=1.2.sp); Spacer(Modifier.height(14.dp))
                        LazyColumn(verticalArrangement=Arrangement.spacedBy(9.dp), modifier=Modifier.weight(1f)) { items((1..pageCount).toList()) { p -> Column(Modifier.fillMaxWidth().clickable { persistPage(); page=p; strokes=loadStrokes(prefs,selected!!.id,p); notes=loadNotes(prefs,selected!!.id,p); selection=null; selectedIndexes=emptySet() }) { Box(Modifier.fillMaxWidth().height(145.dp).border(if(p==page)2.dp else 1.dp,if(p==page)Moss else Color(0xFFE2E1DB),RoundedCornerShape(8.dp)).background(Color(0xFFFFFEFA),RoundedCornerShape(8.dp)),contentAlignment=Alignment.Center) { Text("$p",color=Muted,fontSize=12.sp) }; Text("Sayfa $p",Modifier.align(Alignment.CenterHorizontally).padding(top=5.dp),color=ink,fontSize=11.sp) } } }
                        OutlinedButton(onClick = { addPage() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) { Icon(Icons.Default.Add, null); Text("Sayfa ekle", fontSize = 11.sp) }
                    }
                }
                Row(Modifier.fillMaxWidth().height(42.dp).background(if (dark) Color(0xFF252D28) else Color.White).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) { Text("$page / $pageCount", fontSize = 11.sp, color = Muted); Spacer(Modifier.weight(1f)); Text("${tool.name.lowercase().replaceFirstChar { it.uppercase() }}", fontSize = 11.sp, color = Muted); Spacer(Modifier.width(18.dp)); IconButton(onClick = { zoom = (zoom - .1f).coerceAtLeast(.6f) }, modifier = Modifier.size(30.dp)) { Icon(Icons.Default.Remove, "Uzaklaştır", tint = ink) }; Text("${(zoom * 100).toInt()}%", fontSize = 11.sp, color = Muted); IconButton(onClick = { zoom = (zoom + .1f).coerceAtMost(2f) }, modifier = Modifier.size(30.dp)) { Icon(Icons.Default.Add, "Yakınlaştır", tint = ink) }; TextButton(onClick = { zoom = 1f; pan = Offset.Zero }) { Text("Sıfırla", fontSize = 11.sp) } }
            }
        }
        if (dialog) AlertDialog(onDismissRequest = { dialog = false }, title = { Text("Yeni defter oluştur") }, text = { Column { Text("Fikirlerine yeni bir sayfa aç.", color = Muted); Spacer(Modifier.height(10.dp)); OutlinedTextField(newTitle, { newTitle = it }, label = { Text("Defter adı") }, singleLine = true) } }, confirmButton = { TextButton(onClick = { val title=newTitle.ifBlank { "Adsız defter" }; val b=Book(System.currentTimeMillis(),title,listOf(0xFF496B55,0xFFB47762,0xFF64799D,0xFFC29447)[books.size%4],"Bugün"); persistBooks(listOf(b)+books); selected=b; page=1; pageCount=1; strokes=emptyList(); notes=emptyList(); dialog=false }) { Text("Oluştur") } }, dismissButton = { TextButton(onClick = { dialog=false }) { Text("Vazgeç") } }, shape = RoundedCornerShape(24.dp))
        if (textDialog) AlertDialog(onDismissRequest = { textDialog=false }, title = { Text("Sayfaya metin ekle") }, text = { OutlinedTextField(textDraft,{textDraft=it},label={Text("Metnin")},minLines=2) }, confirmButton = { TextButton(onClick = { if(textDraft.isNotBlank()) { notes=notes+NoteText(textDraft,textPosition.x,textPosition.y,color,width*8); persistPage() }; textDialog=false }) { Text("Ekle") } }, dismissButton = { TextButton(onClick = { textDialog=false }) { Text("İptal") } })
        if (showPenSettings) AlertDialog(onDismissRequest = { showPenSettings = false }, title = { Text("Akıcı kalem motoru") }, text = { Column { Text("Çizgi sabitleme · ${(stabilization * 100).toInt()}%", fontWeight = FontWeight.SemiBold); Slider(value = stabilization, onValueChange = { stabilization = it }, onValueChangeFinished = { prefs.edit().putFloat("stabilization", stabilization).apply() }, valueRange = 0f..0.82f); Text("Daha yüksek değer titremeyi azaltır; çok yükseltmek kalemin ucunda hafif gecikme oluşturabilir.", color = Muted, fontSize = 12.sp); Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = stylusOnly, onCheckedChange = { stylusOnly = it; prefs.edit().putBoolean("stylusOnly", it).apply() }); Text("Yalnızca stylus ile çiz (avuç reddi)", fontSize = 13.sp) }; Text("Basınç duyarlı uç · Catmull–Rom eğri yumuşatma · yuvarlak birleşim ve uçlar", color = Muted, fontSize = 12.sp) } }, confirmButton = { TextButton(onClick = { showPenSettings = false }) { Text("Tamam") } })
        if (showTemplates) AlertDialog(onDismissRequest = { showTemplates=false }, title = { Text("Sayfa şablonu") }, text = { Column { listOf("Çizgili", "Kareli", "Kenar çizgili", "Boş sayfa").forEachIndexed { i,name -> TextButton(onClick={pageStyle=i;prefs.edit().putInt("pageStyle",i).apply();showTemplates=false}) { Icon(Icons.Default.Description,null);Spacer(Modifier.width(12.dp));Text(name) } } } }, confirmButton={TextButton(onClick={showTemplates=false}){Text("Kapat")}})
        if (selected != null && renameDialog) AlertDialog(onDismissRequest={renameDialog=false},title={Text("Defteri yeniden adlandır")},text={OutlinedTextField(newTitle,{newTitle=it},label={Text("Defter adı")})},confirmButton={TextButton(onClick={val b=selected!!;val renamed=b.copy(title=newTitle.ifBlank{b.title});persistBooks(books.map{if(it.id==b.id)renamed else it});selected=renamed;renameDialog=false}){Text("Kaydet")}},dismissButton={TextButton(onClick={renameDialog=false}){Text("İptal")}})
    }
}

private data class RenderedStroke(val stroke: StrokeData, val samples: List<InkSample>)
private fun prepareStroke(stroke: StrokeData): RenderedStroke = RenderedStroke(stroke, if (stroke.kind in setOf("line", "rect", "circle")) emptyList() else InkEngine.smooth(stroke.points.mapIndexed { i, p -> InkSample(p, stroke.pressures.getOrElse(i) { .52f }) }, 6))
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRenderedStroke(rendered: RenderedStroke) {
    val s = rendered.stroke
    if (s.points.isEmpty()) return
    val first = s.points.first()
    if (s.kind == "line" || s.kind == "rect" || s.kind == "circle") {
        val path = Path().apply {
            moveTo(first.x, first.y)
            if (s.kind == "line") s.points.lastOrNull()?.let { lineTo(it.x, it.y) }
            else {
                val end = s.points.last()
                val shape = Rect(minOf(first.x, end.x), minOf(first.y, end.y), maxOf(first.x, end.x), maxOf(first.y, end.y))
                if (s.kind == "rect") addRect(shape) else addOval(shape)
            }
        }
        drawPath(path, s.color, style = Stroke(s.width, cap = StrokeCap.Round))
        return
    }
    val samples = rendered.samples
    if (samples.size == 1) {
        val diameter = InkEngine.width(s.width, samples[0].pressure, 0, 1)
        drawCircle(s.color, diameter / 2f, samples[0].position)
        return
    }
    samples.zipWithNext().forEachIndexed { i, (a, b) ->
        val w = (InkEngine.width(s.width, a.pressure, i, samples.size) + InkEngine.width(s.width, b.pressure, i + 1, samples.size)) / 2f
        drawLine(s.color, a.position, b.position, strokeWidth = w, cap = StrokeCap.Round)
        drawCircle(s.color, w / 2f, b.position)
    }
}
private fun StrokeData.bounds(): Rect { val xs=points.map{it.x};val ys=points.map{it.y};return Rect(xs.minOrNull()?:0f,ys.minOrNull()?:0f,xs.maxOrNull()?:0f,ys.maxOrNull()?:0f) }
@Composable private fun NavItem(icon:String,title:String,active:Boolean,ink:Color,onClick:()->Unit={}) { Row(Modifier.fillMaxWidth().padding(vertical=4.dp).background(if(active)Color(0xFFDCE5DB)else Color.Transparent,RoundedCornerShape(12.dp)).clickable(onClick=onClick).padding(horizontal=12.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically){Text(icon,fontSize=20.sp,color=Moss);Spacer(Modifier.width(14.dp));Text(title,color=ink,fontWeight=if(active)FontWeight.SemiBold else FontWeight.Normal,fontSize=14.sp)} }
@Composable private fun BookCard(book:Book,onOpen:()->Unit,onFavorite:(Book)->Unit,onDelete:(Book)->Unit,onRename:(Book)->Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.width(174.dp)) {
        Box(Modifier.fillMaxWidth().height(196.dp).background(Color(book.color.toInt()),RoundedCornerShape(8.dp)).clickable(onClick=onOpen).padding(8.dp)) {
            Box(Modifier.fillMaxSize().border(1.dp,Color.White.copy(alpha=.23f),RoundedCornerShape(4.dp)));Box(Modifier.width(9.dp).fillMaxHeight().background(Color.Black.copy(alpha=.10f)))
            Column(Modifier.align(Alignment.Center).padding(start=10.dp),horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Default.AutoStories,null,tint=Color.White.copy(alpha=.9f),modifier=Modifier.size(34.dp));Spacer(Modifier.height(12.dp));Text(book.title,color=Color.White,fontWeight=FontWeight.Bold,fontSize=16.sp);Spacer(Modifier.height(5.dp));Text("NOT DEFTERİ",color=Color.White.copy(alpha=.75f),fontSize=9.sp,letterSpacing=2.sp)}
            IconButton(onClick={onFavorite(book)},modifier=Modifier.align(Alignment.TopEnd).size(36.dp)){Icon(if(book.favorite)Icons.Default.Favorite else Icons.Default.FavoriteBorder,null,tint=Color.White)}
        }
        Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f).clickable(onClick=onOpen).padding(top=9.dp)){Text(book.title,fontWeight=FontWeight.SemiBold,color=Ink,fontSize=14.sp);Text("Güncellendi · ${book.updated}",color=Muted,fontSize=11.sp,modifier=Modifier.padding(top=3.dp))};Box{IconButton(onClick={menu=true}){Icon(Icons.Default.MoreHoriz,null,tint=Muted)};DropdownMenu(menu,{menu=false}){DropdownMenuItem(text={Text("Yeniden adlandır")},onClick={menu=false;onRename(book)});DropdownMenuItem(text={Text(if(book.favorite) "Favoriden çıkar" else "Favorilere ekle")},onClick={menu=false;onFavorite(book)});DropdownMenuItem(text={Text("Defteri sil")},leadingIcon={Icon(Icons.Default.Delete,null)},onClick={menu=false;onDelete(book)})} } }
    }
}
@Composable private fun ToolButton(icon:androidx.compose.ui.graphics.vector.ImageVector,label:String,active:Boolean,onClick:()->Unit){Column(Modifier.width(64.dp).padding(top=3.dp).clickable(onClick=onClick),horizontalAlignment=Alignment.CenterHorizontally){Box(Modifier.size(38.dp).background(if(active)Color(0xFFE4ECE3)else Color.Transparent,RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center){Icon(icon,label,tint=if(active)Moss else Muted,modifier=Modifier.size(20.dp))};Text(label,fontSize=8.sp,color=if(active)Moss else Muted,maxLines=1)} }
private fun loadBooks(p:android.content.SharedPreferences):List<Book>{val s=p.getString("books",null)?:return listOf(Book(1,"Günlük",0xFF496B55,"Bugün"),Book(2,"Ders notları",0xFF64799D,"Dün"),Book(3,"Fikir defteri",0xFFB47762,"2 gün önce"));return try{val a=JSONArray(s);(0 until a.length()).map{val o=a.getJSONObject(it);Book(o.getLong("id"),o.getString("title"),o.getLong("color"),o.getString("updated"),o.optBoolean("favorite"))}}catch(_:Exception){emptyList()} }
private fun booksToJson(books:List<Book>)=JSONArray().apply{books.forEach{put(JSONObject().put("id",it.id).put("title",it.title).put("color",it.color).put("updated",it.updated).put("favorite",it.favorite))}}.toString()
private fun saveStrokes(p:android.content.SharedPreferences,id:Long,page:Int,strokes:List<StrokeData>){val arr=JSONArray();strokes.forEach{s->val pts=JSONArray();s.points.forEach{pts.put(JSONArray().put(it.x).put(it.y))};val pressure=JSONArray();s.pressures.forEach{pressure.put(it)};arr.put(JSONObject().put("points",pts).put("pressure",pressure).put("color",s.color.value.toLong()).put("width",s.width).put("kind",s.kind))};p.edit().putString("strokes_${id}_$page",arr.toString()).apply()}
private fun loadStrokes(p:android.content.SharedPreferences,id:Long,page:Int):List<StrokeData> = try{val a=JSONArray(p.getString("strokes_${id}_$page","[]"));(0 until a.length()).map{val o=a.getJSONObject(it);val pts=o.getJSONArray("points");StrokeData((0 until pts.length()).map{i->Offset(pts.getJSONArray(i).getDouble(0).toFloat(),pts.getJSONArray(i).getDouble(1).toFloat())},Color(o.getLong("color").toULong().toLong()),o.getDouble("width").toFloat(),o.optString("kind","pen"),o.optJSONArray("pressure")?.let{a->(0 until a.length()).map{a.getDouble(it).toFloat()}}?:emptyList())}}catch(_:Exception){emptyList()}
private fun saveNotes(p:android.content.SharedPreferences,id:Long,page:Int,notes:List<NoteText>){val a=JSONArray();notes.forEach{a.put(JSONObject().put("text",it.text).put("x",it.x).put("y",it.y).put("color",it.color.value.toLong()).put("size",it.size))};p.edit().putString("notes_${id}_$page",a.toString()).apply()}
private fun loadNotes(p:android.content.SharedPreferences,id:Long,page:Int):List<NoteText> = try{val a=JSONArray(p.getString("notes_${id}_$page","[]"));(0 until a.length()).map{val o=a.getJSONObject(it);NoteText(o.getString("text"),o.getDouble("x").toFloat(),o.getDouble("y").toFloat(),Color(o.getLong("color").toULong().toLong()),o.getDouble("size").toFloat())}}catch(_:Exception){emptyList()}
private fun exportPdfToUri(context:android.content.Context,uri:android.net.Uri,book:Book,pages:Int,style:Int){try{val pdf=android.graphics.pdf.PdfDocument();for(n in 1..pages){val info=android.graphics.pdf.PdfDocument.PageInfo.Builder(1200,1600,n).create();val page=pdf.startPage(info);val c=page.canvas;val paint=NativePaint(NativePaint.ANTI_ALIAS_FLAG);paint.color=android.graphics.Color.rgb(255,254,250);c.drawColor(paint.color);paint.color=android.graphics.Color.rgb(220,220,214);if(style==0||style==2)for(y in 140..1550 step 55)c.drawLine(70f,y.toFloat(),1130f,y.toFloat(),paint);if(style==1){for(y in 140..1550 step 55)c.drawLine(70f,y.toFloat(),1130f,y.toFloat(),paint);for(x in 70..1130 step 55)c.drawLine(x.toFloat(),120f,x.toFloat(),1550f,paint)};if(style==2)c.drawLine(160f,120f,160f,1550f,paint);paint.color=android.graphics.Color.rgb(55,75,60);paint.textSize=36f;c.drawText(book.title,75f,80f,paint);val lines=loadStrokes(context.getSharedPreferences("papertrail",0),book.id,n);lines.forEach{s->paint.color=s.color.toArgb();paint.strokeCap=NativePaint.Cap.ROUND;paint.style=NativePaint.Style.STROKE;val samples=InkEngine.smooth(s.points.mapIndexed{i,p->InkSample(p,s.pressures.getOrElse(i){.52f})},6);if(samples.size>1)samples.zipWithNext().forEachIndexed{i,(a,b)->paint.strokeWidth=(InkEngine.width(s.width,a.pressure,i,samples.size)+InkEngine.width(s.width,b.pressure,i+1,samples.size))/2f;c.drawLine(a.position.x,a.position.y,b.position.x,b.position.y,paint)}else samples.firstOrNull()?.let{paint.style=NativePaint.Style.FILL;c.drawCircle(it.position.x,it.position.y,InkEngine.width(s.width,it.pressure,0,1)/2f,paint)}};loadNotes(context.getSharedPreferences("papertrail",0),book.id,n).forEach{paint.style=NativePaint.Style.FILL;paint.color=it.color.toArgb();paint.textSize=it.size;c.drawText(it.text,it.x,it.y,paint)};pdf.finishPage(page)};context.contentResolver.openOutputStream(uri)?.use{pdf.writeTo(it)}?:error("Dosya açılamadı");pdf.close();android.widget.Toast.makeText(context,"${pages} sayfalı PDF dışa aktarıldı",android.widget.Toast.LENGTH_LONG).show()}catch(e:Exception){android.widget.Toast.makeText(context,"PDF oluşturulamadı: ${e.message}",android.widget.Toast.LENGTH_LONG).show()} }
