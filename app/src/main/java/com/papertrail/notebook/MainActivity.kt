package com.papertrail.notebook

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

private val Ink = Color(0xFF25332C)
private val Moss = Color(0xFF496B55)
private val Paper = Color(0xFFF7F4ED)
private val Muted = Color(0xFF888C83)
private data class StrokeData(val points: List<Offset>, val color: Color, val width: Float, val erase: Boolean = false)
private data class Book(val id: Long, val title: String, val color: Long, val updated: String)
private enum class Tool { PEN, HIGHLIGHTER, ERASER, TEXT, LASSO }

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
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
    var pageStyle by remember { mutableStateOf(0) }
    var page by remember { mutableIntStateOf(1) }
    var tool by remember { mutableStateOf(Tool.PEN) }
    var color by remember { mutableStateOf(Color(0xFF243C32)) }
    var width by remember { mutableFloatStateOf(3.5f) }
    var strokes by remember(selected) { mutableStateOf(loadStrokes(prefs, selected?.id ?: -1)) }
    var redo by remember { mutableStateOf<List<StrokeData>>(emptyList()) }
    var draft by remember { mutableStateOf<StrokeData?>(null) }
    var dark by remember { mutableStateOf(false) }
    val bg = if (dark) Color(0xFF202722) else Paper
    val ink = if (dark) Color(0xFFE9E6DD) else Ink

    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFFA8C8A7), background = bg, surface = Color(0xFF2A332D)) else lightColorScheme(primary = Moss, background = bg, surface = Color.White)) {
        if (selected == null) {
            Row(Modifier.fillMaxSize().background(bg)) {
                Column(Modifier.width(220.dp).fillMaxHeight().background(if (dark) Color(0xFF252D28) else Color(0xFFECE9E0)).padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(36.dp).background(Moss, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.EditNote, null, tint = Color.White) }; Spacer(Modifier.width(11.dp)); Text("papertrail", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = ink) }
                    Spacer(Modifier.height(52.dp)); NavItem("⌂", stringResourceSafe("library", "Kitaplığım"), true, ink)
                    NavItem("◷", "Son kullanılanlar", false, ink)
                    NavItem("☆", "Favoriler", false, ink)
                    Spacer(Modifier.weight(1f)); NavItem("⚙", "Ayarlar", false, ink)
                    Text("Sakin bir alan, berrak fikirler.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 20.dp))
                }
                Column(Modifier.fillMaxSize().padding(horizontal = 42.dp, vertical = 32.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("İyi günler ☀", color = Moss, fontSize = 14.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(8.dp)); Text("Notların, senin alanın.", color = ink, fontSize = 30.sp, fontWeight = FontWeight.Bold) }
                        OutlinedButton(onClick = { dark = !dark }, shape = RoundedCornerShape(14.dp)) { Icon(if (dark) Icons.Default.LightMode else Icons.Default.DarkMode, null); Spacer(Modifier.width(8.dp)); Text("Görünüm") }
                        Spacer(Modifier.width(12.dp)); Button(onClick = { newTitle = ""; dialog = true }, shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Yeni defter") }
                    }
                    Spacer(Modifier.height(26.dp))
                    OutlinedTextField(value = search, onValueChange = { search = it }, placeholder = { Text("Defterlerinde ara…") }, leadingIcon = { Icon(Icons.Default.Search, null, tint = Muted) }, modifier = Modifier.fillMaxWidth(0.63f), shape = RoundedCornerShape(16.dp), singleLine = true)
                    Spacer(Modifier.height(28.dp)); Row(verticalAlignment = Alignment.CenterVertically) { Text("Defter rafın", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = ink); Spacer(Modifier.width(12.dp)); Text("${books.size} defter", color = Muted, fontSize = 13.sp) }
                    Spacer(Modifier.height(18.dp))
                    val filtered = books.filter { it.title.contains(search, true) }
                    if (filtered.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("✦", fontSize = 38.sp, color = Moss); Spacer(Modifier.height(8.dp)); Text(if (search.isBlank()) "İlk defterini oluştur" else "Eşleşen defter bulunamadı", color = Muted); Spacer(Modifier.height(14.dp)); TextButton(onClick = { newTitle = ""; dialog = true }) { Text("+  Yeni defter oluştur") } } }
                    else LazyColumn(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                        items(filtered.chunked(4)) { rowBooks -> Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) { rowBooks.forEach { book -> BookCard(book) { selected = book; page = 1; strokes = loadStrokes(prefs, book.id) } } } }
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().background(bg)) {
                Row(Modifier.fillMaxWidth().height(66.dp).background(if (dark) Color(0xFF252D28) else Color.White).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selected = null }) { Icon(Icons.Default.ArrowBack, "Kitaplığa dön", tint = ink) }
                    Column(Modifier.widthIn(max = 230.dp)) { Text(selected!!.title, color = ink, fontWeight = FontWeight.Bold, fontSize = 16.sp); Text("Kaydedildi", color = Muted, fontSize = 11.sp) }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { if (strokes.isNotEmpty()) { redo = redo + strokes.last(); strokes = strokes.dropLast(1); saveStrokes(prefs, selected!!.id, strokes) } }) { Icon(Icons.Default.Undo, "Geri al", tint = ink) }
                    IconButton(onClick = { if (redo.isNotEmpty()) { strokes = strokes + redo.last(); redo = redo.dropLast(1); saveStrokes(prefs, selected!!.id, strokes) } }) { Icon(Icons.Default.Redo, "Yinele", tint = ink) }
                    VerticalDivider(Modifier.height(26.dp).padding(horizontal = 10.dp))
                    IconButton(onClick = { showTemplates = true }) { Icon(Icons.Default.GridView, "Şablon", tint = ink) }
                    TextButton(onClick = { exportPdf(context, selected!!.title, pageStyle) }) { Icon(Icons.Default.IosShare, null); Spacer(Modifier.width(5.dp)); Text("PDF dışa aktar") }
                }
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.width(68.dp).fillMaxHeight().background(if (dark) Color(0xFF252D28) else Color.White), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        ToolButton(Icons.Default.Edit, "Kalem", tool == Tool.PEN) { tool = Tool.PEN }
                        ToolButton(Icons.Default.Brush, "Vurgulayıcı", tool == Tool.HIGHLIGHTER) { tool = Tool.HIGHLIGHTER }
                        ToolButton(Icons.Default.AutoFixNormal, "Silgi", tool == Tool.ERASER) { tool = Tool.ERASER }
                        ToolButton(Icons.Default.TextFields, "Metin", tool == Tool.TEXT) { tool = Tool.TEXT }
                        ToolButton(Icons.Default.Gesture, "Kement", tool == Tool.LASSO) { tool = Tool.LASSO }
                        HorizontalDivider(Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
                        listOf(Color(0xFF243C32), Color(0xFF334D78), Color(0xFFB44B48), Color(0xFFE5AA48), Color(0xFF242424)).forEach { c -> Box(Modifier.padding(4.dp).size(25.dp).background(c, CircleShape).then(if (c == color) Modifier.border(2.dp, if (dark) Color.White else Moss, CircleShape) else Modifier).clickable { color = c; tool = Tool.PEN }) }
                        Spacer(Modifier.weight(1f)); IconButton(onClick = { strokes = emptyList(); saveStrokes(prefs, selected!!.id, strokes) }) { Icon(Icons.Default.DeleteOutline, "Temizle", tint = Muted) }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight().background(if (dark) Color(0xFF313833) else Color(0xFFE8E6E0)), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize().padding(horizontal = 44.dp, vertical = 22.dp).pointerInput(tool, color, width, strokes) {
                            detectDragGestures(onDragStart = { p -> if (tool == Tool.PEN || tool == Tool.HIGHLIGHTER || tool == Tool.ERASER) draft = StrokeData(listOf(p), if (tool == Tool.ERASER) Color(0xFFFDFCF8) else color, if (tool == Tool.HIGHLIGHTER) width * 4 else width, tool == Tool.ERASER) }, onDrag = { change, _ -> change.consume(); draft = draft?.copy(points = draft!!.points + change.position) }, onDragEnd = { draft?.let { strokes = strokes + it; redo = emptyList(); saveStrokes(prefs, selected!!.id, strokes) }; draft = null })
                        }) {
                            drawRect(if (dark) Color(0xFF2C332E) else Color(0xFFFFFEFA))
                            val step = 34.dp.toPx()
                            when (pageStyle) { 0 -> { var y = step; while (y < size.height) { drawLine(if (dark) Color(0xFF3D463F) else Color(0xFFE6E4DC), Offset(0f,y), Offset(size.width,y), 1f); y += step } }; 1 -> { var x=step; var y=step; while (y<size.height) { drawLine(Color(0xFFE8E6DE),Offset(0f,y),Offset(size.width,y),1f); y+=step }; while(x<size.width){drawLine(Color(0xFFE8E6DE),Offset(x,0f),Offset(x,size.height),1f);x+=step} }; 2 -> { var y=step; while(y<size.height){drawLine(Color(0xFFE6E4DC),Offset(0f,y),Offset(size.width,y),1f);y+=step}; drawLine(Color(0xFFE9B7B0),Offset(step*2,0f),Offset(step*2,size.height),1.2f) } }
                            (strokes + listOfNotNull(draft)).forEach { s -> if (s.points.size == 1) drawCircle(s.color, s.width/2, s.points.first()) else { val path=Path().apply { moveTo(s.points.first().x,s.points.first().y); s.points.drop(1).forEach { lineTo(it.x,it.y) } }; drawPath(path,s.color,style=Stroke(s.width,cap=StrokeCap.Round)) } }
                        }
                    }
                    Column(Modifier.width(168.dp).fillMaxHeight().background(if (dark) Color(0xFF252D28) else Color.White).padding(14.dp)) {
                        Text("SAYFALAR", fontSize=11.sp, color=Muted, fontWeight=FontWeight.Bold, letterSpacing=1.2.sp); Spacer(Modifier.height(16.dp))
                        Box(Modifier.fillMaxWidth().height(180.dp).border(2.dp, Moss, RoundedCornerShape(8.dp)).background(Color(0xFFFFFEFA), RoundedCornerShape(8.dp)).clickable { }) { Text("$page", Modifier.align(Alignment.BottomEnd).padding(7.dp), color=Muted, fontSize=11.sp) }
                        Text("Sayfa $page", Modifier.align(Alignment.CenterHorizontally).padding(top=8.dp), color=ink, fontSize=12.sp)
                        Spacer(Modifier.weight(1f)); OutlinedButton(onClick = { page++; strokes = emptyList() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.Add, null); Text("Sayfa ekle") }
                    }
                }
                Row(Modifier.fillMaxWidth().height(36.dp).background(if (dark) Color(0xFF252D28) else Color.White).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) { Text("${page} / ${page}", fontSize = 11.sp, color = Muted); Spacer(Modifier.weight(1f)); Text("${tool.name.lowercase().replaceFirstChar { it.uppercase() }}  ·  %100", fontSize = 11.sp, color = Muted) }
            }
        }
        if (dialog) AlertDialog(onDismissRequest = { dialog = false }, title = { Text("Yeni defter oluştur") }, text = { Column { Text("Fikirlerine yeni bir sayfa aç.", color = Muted); Spacer(Modifier.height(10.dp)); OutlinedTextField(newTitle, { newTitle = it }, label = { Text("Defter adı") }, singleLine = true) } }, confirmButton = { TextButton(onClick = { val title = newTitle.ifBlank { "Adsız defter" }; val book = Book(System.currentTimeMillis(), title, listOf(0xFF496B55,0xFFB47762,0xFF64799D,0xFFC29447)[books.size % 4], "Bugün"); books = listOf(book) + books; prefs.edit().putString("books", booksToJson(books)).apply(); selected = book; strokes = emptyList(); dialog = false }) { Text("Oluştur") } }, dismissButton = { TextButton(onClick = { dialog = false }) { Text("Vazgeç") } }, shape = RoundedCornerShape(24.dp))
        if (showTemplates) AlertDialog(onDismissRequest = { showTemplates = false }, title = { Text("Sayfa şablonu") }, text = { Column { listOf("Çizgili", "Kareli", "Müzik defteri", "Boş sayfa").forEachIndexed { i, name -> TextButton(onClick = { pageStyle = i.coerceAtMost(2); showTemplates = false }) { Icon(Icons.Default.Description, null); Spacer(Modifier.width(12.dp)); Text(name) } } } }, confirmButton = { TextButton(onClick = { showTemplates = false }) { Text("Kapat") } })
    }
}

@Composable private fun NavItem(icon: String, title: String, active: Boolean, ink: Color) { Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(if(active) Color(0xFFDCE5DB) else Color.Transparent, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Text(icon, fontSize = 20.sp, color = Moss); Spacer(Modifier.width(14.dp)); Text(title, color = ink, fontWeight = if(active) FontWeight.SemiBold else FontWeight.Normal, fontSize = 14.sp) } }
@Composable private fun BookCard(book: Book, onClick: () -> Unit) {
    Column(Modifier.width(174.dp).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().height(196.dp).background(Color(book.color.toInt()), RoundedCornerShape(8.dp)).padding(8.dp)) {
            Box(Modifier.fillMaxSize().border(1.dp, Color.White.copy(alpha=.23f), RoundedCornerShape(4.dp)))
            Box(Modifier.width(9.dp).fillMaxHeight().background(Color.Black.copy(alpha=.10f)))
            Column(Modifier.align(Alignment.Center).padding(start=10.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.AutoStories, null, tint=Color.White.copy(alpha=.9f), modifier=Modifier.size(34.dp)); Spacer(Modifier.height(12.dp)); Text(book.title, color=Color.White, fontWeight=FontWeight.Bold, fontSize=16.sp); Spacer(Modifier.height(5.dp)); Text("NOT DEFTERİ", color=Color.White.copy(alpha=.75f), fontSize=9.sp, letterSpacing=2.sp) }
        }
        Spacer(Modifier.height(11.dp)); Text(book.title, fontWeight=FontWeight.SemiBold, color=Ink, fontSize=14.sp); Text("Güncellendi · ${book.updated}", color=Muted, fontSize=11.sp, modifier=Modifier.padding(top=3.dp))
    }
}
@Composable private fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, onClick: () -> Unit) { Column(Modifier.width(58.dp).padding(top=7.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) { Box(Modifier.size(42.dp).background(if(active) Color(0xFFE4ECE3) else Color.Transparent, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) { Icon(icon, label, tint=if(active) Moss else Muted) }; Text(label, fontSize=9.sp, color=if(active) Moss else Muted, maxLines=1) } }
@Composable private fun stringResourceSafe(key: String, fallback: String) = fallback
private fun loadBooks(prefs: android.content.SharedPreferences): List<Book> { val s=prefs.getString("books",null) ?: return listOf(Book(1,"Günlük",0xFF496B55,"Bugün"),Book(2,"Ders notları",0xFF64799D,"Dün"),Book(3,"Fikir defteri",0xFFB47762,"2 gün önce")); return try { val a=JSONArray(s); (0 until a.length()).map { val o=a.getJSONObject(it); Book(o.getLong("id"),o.getString("title"),o.getLong("color"),o.getString("updated")) } } catch (_:Exception) { emptyList() } }
private fun booksToJson(books: List<Book>) = JSONArray().apply { books.forEach { put(JSONObject().put("id",it.id).put("title",it.title).put("color",it.color).put("updated",it.updated)) } }.toString()
private fun saveStrokes(prefs: android.content.SharedPreferences,id: Long,strokes: List<StrokeData>) { val json=JSONArray(); strokes.forEach { s -> val pts=JSONArray(); s.points.forEach { pts.put(JSONArray().put(it.x).put(it.y)) }; json.put(JSONObject().put("points",pts).put("color",s.color.value.toLong()).put("width",s.width).put("erase",s.erase)) }; prefs.edit().putString("strokes_$id",json.toString()).apply() }
private fun loadStrokes(prefs: android.content.SharedPreferences,id: Long): List<StrokeData> = try { val a=JSONArray(prefs.getString("strokes_$id","[]")); (0 until a.length()).map { val o=a.getJSONObject(it); val p=o.getJSONArray("points"); StrokeData((0 until p.length()).map { i -> Offset(p.getJSONArray(i).getDouble(0).toFloat(),p.getJSONArray(i).getDouble(1).toFloat()) },Color(o.getLong("color").toULong().toLong()),o.getDouble("width").toFloat(),o.optBoolean("erase")) } } catch (_:Exception) { emptyList() }
private fun exportPdf(context: android.content.Context, title: String, style: Int) { try { val doc=android.graphics.pdf.PdfDocument(); val info=android.graphics.pdf.PdfDocument.PageInfo.Builder(1200,1600,1).create(); val p=doc.startPage(info); val paint=android.graphics.Paint().apply { color=android.graphics.Color.rgb(255,254,250) }; p.canvas.drawRect(0f,0f,1200f,1600f,paint); paint.color=android.graphics.Color.rgb(50,70,55); paint.textSize=38f; p.canvas.drawText(title,90f,100f,paint); paint.color=android.graphics.Color.rgb(225,225,218); for(y in 160..1550 step 55) p.canvas.drawLine(80f,y.toFloat(),1120f,y.toFloat(),paint); doc.finishPage(p); val file=java.io.File(context.cacheDir,"${title.replace(" ","_")}.pdf"); doc.writeTo(java.io.FileOutputStream(file)); doc.close(); android.widget.Toast.makeText(context,"PDF oluşturuldu: ${file.name}",android.widget.Toast.LENGTH_LONG).show() } catch(e:Exception) { android.widget.Toast.makeText(context,"PDF oluşturulamadı",android.widget.Toast.LENGTH_SHORT).show() } }
