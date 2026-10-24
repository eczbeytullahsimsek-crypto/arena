package com.papertrail.notebook

import android.graphics.Paint as NativePaint
import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min
import kotlin.math.sqrt

private val Ink = Color(0xFF27352E)
private val Moss = Color(0xFF55735B)
private val Paper = Color(0xFFF5F1E8)
private val Muted = Color(0xFF85877E)
private val Line = Color(0xFFE5E0D5)
private val Accent = Color(0xFFCF805E)
internal data class StrokeData(val points: List<Offset>, val color: Color, val width: Float, val kind: String = "pen", val pressures: List<Float> = emptyList())
internal data class NoteText(val text: String, val x: Float, val y: Float, val color: Color, val size: Float)
internal data class Book(val id: Long, val title: String, val color: Long, val updated: String, val favorite: Boolean = false)
internal enum class Tool(val glyph: String, val label: String) { PEN("✳", "Kalem"), HIGHLIGHTER("▱", "Vurgula"), ERASER("⌫", "Silgi"), TEXT("T", "Metin"), LASSO("⌁", "Kement"), LINE("╱", "Çizgi"), RECTANGLE("▭", "Kutu"), CIRCLE("◯", "Daire") }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(245, 241, 232)
        window.navigationBarColor = android.graphics.Color.rgb(245, 241, 232)
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContent { PapertrailApp() }
    }
}

@Composable
private fun PapertrailApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("papertrail", 0) }
    var books by remember { mutableStateOf(loadBooks(prefs)) }
    var selected by remember { mutableStateOf<Book?>(null) }
    var query by remember { mutableStateOf("") }
    var dark by remember { mutableStateOf(false) }
    var createDialog by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var textDialog by remember { mutableStateOf(false) }
    var penDialog by remember { mutableStateOf(false) }
    var templateDialog by remember { mutableStateOf(false) }
    var actionDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Book?>(null) }
    var renameTarget by remember { mutableStateOf<Book?>(null) }
    var titleInput by remember { mutableStateOf("") }
    var textInput by remember { mutableStateOf("") }
    var textPosition by remember { mutableStateOf(Offset(120f, 160f)) }
    var pageStyle by remember { mutableIntStateOf(prefs.getInt("pageStyle", 0)) }
    var page by remember { mutableIntStateOf(1) }
    var pageCount by remember(selected) { mutableIntStateOf(selected?.let { prefs.getInt("count_${it.id}", 1) } ?: 1) }
    var tool by remember { mutableStateOf(Tool.PEN) }
    var color by remember { mutableStateOf(Color(0xFF263E32)) }
    var width by remember { mutableFloatStateOf(3.4f) }
    var stabilization by remember { mutableFloatStateOf(prefs.getFloat("stabilization", .22f)) }
    var stylusOnly by remember { mutableStateOf(prefs.getBoolean("stylusOnly", false)) }
    val zoomState = remember { mutableFloatStateOf(1f) }
    var zoomReadout by remember { mutableIntStateOf(100) }
    val panState = remember { mutableStateOf(Offset.Zero) }
    var strokes by remember { mutableStateOf(emptyList<StrokeData>()) }
    var notes by remember { mutableStateOf(emptyList<NoteText>()) }
    var pageLoading by remember { mutableStateOf(false) }
    val ioScope = rememberCoroutineScope()
    var redo by remember(selected?.id, page) { mutableStateOf<List<StrokeData>>(emptyList()) }
    var selection by remember { mutableStateOf<Rect?>(null) }
    var selectedIndexes by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val background = if (dark) Color(0xFF202722) else Paper
    val foreground = if (dark) Color(0xFFF0EEE7) else Ink
    SideEffect {
        (context as? Activity)?.window?.let { window ->
            val editor = selected != null
            window.statusBarColor = if (editor) android.graphics.Color.rgb(24,54,95) else android.graphics.Color.rgb(245,241,232)
            window.navigationBarColor = if (editor) android.graphics.Color.rgb(24,54,95) else android.graphics.Color.rgb(245,241,232)
            window.decorView.systemUiVisibility = if (editor) 0 else android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> if (uri != null && selected != null) exportPdfToUri(context, uri, selected!!, pageCount, pageStyle) }
    fun saveBooks(value: List<Book>) { books = value; prefs.edit().putString("books", booksToJson(value)).apply() }
    fun savePage() { val bookId=selected?.id ?: return; if(pageLoading)return; val pageId=page; val savedStrokes=strokes; val savedNotes=notes; ioScope.launch(Dispatchers.IO){saveStrokes(prefs,bookId,pageId,savedStrokes);saveNotes(prefs,bookId,pageId,savedNotes)} }
    fun goToPage(next: Int) { savePage(); pageLoading=true; page = next.coerceIn(1, pageCount); selection = null; selectedIndexes = emptySet(); redo = emptyList() }
    fun createPage() { savePage(); pageLoading=true; pageCount++; selected?.let { prefs.edit().putInt("count_${it.id}", pageCount).apply() }; page = pageCount; strokes = emptyList(); notes = emptyList(); redo = emptyList(); selection = null }

    LaunchedEffect(selected?.id, page) {
        val id=selected?.id
        if(id==null){strokes=emptyList();notes=emptyList();pageLoading=false}
        else {pageLoading=true;val loaded=withContext(Dispatchers.IO){loadStrokes(prefs,id,page) to loadNotes(prefs,id,page)};strokes=loaded.first;notes=loaded.second;pageLoading=false}
    }

    Box(Modifier.fillMaxSize().background(background)) {
        if (selected == null) {
            val visibleBooks = books.filter { b -> if (query == "#favorite") b.favorite else query.isBlank() || b.title.contains(query, true) || (1..prefs.getInt("count_${b.id}", 1)).any { p -> loadNotes(prefs, b.id, p).any { it.text.contains(query, true) } } }
            LibraryScreen(visibleBooks, query, { query = it }, dark, { dark = !dark },
                onCreate = { titleInput = ""; createDialog = true },
                onOpen = { book ->
                    val count = prefs.getInt("count_${book.id}", 1)
                    val matchPage = if (query.isNotBlank() && query != "#favorite" && !book.title.contains(query, true)) (1..count).firstOrNull { p -> loadNotes(prefs, book.id, p).any { it.text.contains(query, true) } } ?: 1 else 1
                    selected = book; page = matchPage; pageCount = count; pageLoading = true; panState.value = Offset.Zero; zoomState.floatValue = 1f; zoomReadout = 100
                },
                onFavorite = { b -> saveBooks(books.map { if (it.id == b.id) it.copy(favorite = !it.favorite) else it }) },
                onRename = { b -> titleInput = b.title; renameTarget = b; renameDialog = true },
                onDelete = { deleteTarget = it })
        } else {
            Column(Modifier.fillMaxSize()) {
                EditorHeader(selected!!, page, pageCount, pageLoading,
                    onBack = { savePage(); pageLoading = false; selected = null },
                    onPrevious = { if(page>1)goToPage(page-1) },
                    onNext = { if(page<pageCount)goToPage(page+1) },
                    onTemplates = { templateDialog = true }, onMore = { actionDialog = true })
                EditorToolbar(tool, color, width, dark,
                    onUndo = { if (strokes.isNotEmpty()) { redo = redo + strokes.last(); strokes = strokes.dropLast(1); savePage() } },
                    onRedo = { if (redo.isNotEmpty()) { strokes = strokes + redo.last(); redo = redo.dropLast(1); savePage() } },
                    onTool = { tool = it }, onColor = { color = it; if(tool==Tool.ERASER)tool=Tool.PEN },
                    onWidth = { width = it }, onAddPage = { createPage() })
                DrawingSurface(
                    modifier = Modifier.weight(1f).fillMaxWidth(), dark = dark, pageStyle = pageStyle,
                    tool = tool, color = color, width = width, stabilization = stabilization, stylusOnly = stylusOnly,
                    strokes = strokes, notes = notes, selection = selection, selectedIndexes = selectedIndexes, enabled = !pageLoading,
                    zoomState = zoomState, panState = panState,
                    onCommit = { ink -> strokes = strokes + ink; redo = emptyList(); savePage() },
                    onMove = { moved, bounds -> strokes = moved; selection = bounds; savePage() },
                    onSelect = { bounds, indexes -> selection = bounds; selectedIndexes = indexes },
                    onText = { p -> textPosition = p; textInput = ""; textDialog = true },
                    onZoomSettled = { zoomReadout = (it * 100).toInt() }
                )
                Row(Modifier.fillMaxWidth().height(38.dp).background(if(dark)Color(0xFF18345B)else Color(0xFF244A7A)).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){
                    Text("${page} / ${pageCount}",color=Color.White,fontSize=10.sp,fontWeight=FontWeight.SemiBold)
                    Spacer(Modifier.width(16.dp));Text(if(pageLoading)"Sayfa yükleniyor…" else "Tüm değişiklikler kaydedildi",color=Color.White.copy(alpha=.75f),fontSize=9.sp)
                    Spacer(Modifier.weight(1f))
                    if(selectedIndexes.isNotEmpty()){Text("${selectedIndexes.size} öğe seçildi",color=Color.White,fontSize=9.sp);Text("Sil",Modifier.padding(horizontal=9.dp).clip(RoundedCornerShape(8.dp)).clickable{strokes=strokes.filterIndexed{i,_->i !in selectedIndexes};selectedIndexes=emptySet();selection=null;savePage()}.padding(6.dp),color=Color.White,fontSize=10.sp);Text("Kopyala",Modifier.padding(end=10.dp).clip(RoundedCornerShape(8.dp)).clickable{val copies=selectedIndexes.mapNotNull{strokes.getOrNull(it)}.map{s->s.copy(points=s.points.map{Offset(it.x+22,it.y+22)})};strokes+=copies;selectedIndexes=emptySet();selection=null;savePage()}.padding(6.dp),color=Color.White,fontSize=10.sp)}
                    Text("${zoomReadout}%",color=Color.White,fontSize=10.sp);GlyphControl("−"){zoomState.floatValue=(zoomState.floatValue-.1f).coerceAtLeast(.55f);zoomReadout=(zoomState.floatValue*100).toInt()};GlyphControl("+"){zoomState.floatValue=(zoomState.floatValue+.1f).coerceAtMost(3f);zoomReadout=(zoomState.floatValue*100).toInt()};GlyphControl("↺"){zoomState.floatValue=1f;zoomReadout=100;panState.value=Offset.Zero}
                }
            }
        }

        if (createDialog) PaperDialog("Yeni defter", "Fikirlerini toplamaya başlayacağın deftere bir ad ver.", { createDialog=false }, {
            EntryField(titleInput,{titleInput=it},"Defter adı");Spacer(Modifier.height(20.dp));Row(horizontalArrangement=Arrangement.End,modifier=Modifier.fillMaxWidth()){ActionButton("×","Vazgeç",false){createDialog=false};Spacer(Modifier.width(8.dp));ActionButton("+","Oluştur",true){val title=titleInput.ifBlank{"Adsız defter"};val b=Book(System.currentTimeMillis(),title,listOf(0xFF55735B,0xFFB47762,0xFF64799D,0xFFC29447)[books.size%4],"Bugün");saveBooks(listOf(b)+books);selected=b;page=1;pageCount=1;pageLoading=true;strokes=emptyList();notes=emptyList();createDialog=false}}
        })
        if (renameDialog) PaperDialog("Defteri yeniden adlandır", "Kitaplığındaki başlığı güncelle.", {renameDialog=false;renameTarget=null}, {EntryField(titleInput,{titleInput=it},"Defter adı");Spacer(Modifier.height(20.dp));Row(horizontalArrangement=Arrangement.End,modifier=Modifier.fillMaxWidth()){ActionButton("×","İptal",false){renameDialog=false;renameTarget=null};Spacer(Modifier.width(8.dp));ActionButton("✓","Kaydet",true){val target=renameTarget;if(target!=null){val renamed=target.copy(title=titleInput.ifBlank{target.title});saveBooks(books.map{if(it.id==target.id)renamed else it});if(selected?.id==target.id)selected=renamed};renameDialog=false;renameTarget=null}}})
        if (textDialog) PaperDialog("Tuvale metin ekle", "Metin seçtiğin noktada yerleştirilir.", {textDialog=false}, {EntryField(textInput,{textInput=it},"Metin",multiline=true);Spacer(Modifier.height(18.dp));Row(horizontalArrangement=Arrangement.End,modifier=Modifier.fillMaxWidth()){ActionButton("×","İptal",false){textDialog=false};Spacer(Modifier.width(8.dp));ActionButton("+","Ekle",true){if(textInput.isNotBlank()){notes=notes+NoteText(textInput,textPosition.x,textPosition.y,color,width*8);savePage()};textDialog=false}}})
        if (penDialog) PaperDialog("Kalem motoru", "Kontrolü artır, gecikmeyi düşük tut.", {penDialog=false}, {Text("ÇİZGİ SABİTLEME   ${(stabilization*100).toInt()}%",color=Muted,fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=1.sp);MiniSlider(stabilization,{stabilization=it;prefs.edit().putFloat("stabilization",it).apply()},0f..0.82f,Modifier.fillMaxWidth());Row(verticalAlignment=Alignment.CenterVertically){TogglePill(stylusOnly){stylusOnly=it;prefs.edit().putBoolean("stylusOnly",it).apply()};Spacer(Modifier.width(10.dp));Text("Yalnızca stylus ile çiz",color=foreground,fontSize=13.sp)};Text("Basınca duyarlı uç · eğri enterpolasyonu · hız uyarlamalı sabitleme",Modifier.padding(top=12.dp),color=Muted,fontSize=11.sp);Spacer(Modifier.height(12.dp));ActionButton("✓","Tamam",true){penDialog=false}})
        if (templateDialog) PaperDialog("Sayfa karakteri", "Bir kâğıt düzeni seç.", {templateDialog=false}, {listOf("Çizgili","Kareli","Kenar çizgili","Boş").forEachIndexed{i,label->Row(Modifier.fillMaxWidth().padding(vertical=5.dp).clip(RoundedCornerShape(14.dp)).background(if(pageStyle==i)Color(0xFFE8EDE5)else Color(0xFFF5F2EB)).clickable{pageStyle=i;prefs.edit().putInt("pageStyle",i).apply();templateDialog=false}.padding(15.dp),verticalAlignment=Alignment.CenterVertically){Text(if(pageStyle==i)"●" else "○",color=Moss);Spacer(Modifier.width(12.dp));Text(label,color=Ink,fontSize=14.sp)}};Spacer(Modifier.height(12.dp));ActionButton("✓","Uygula",true){templateDialog=false}})
        if (actionDialog) PaperDialog(selected!!.title,"Defter araçları",{actionDialog=false},{
            MenuRow("✦","Kalem motoru ayarları"){penDialog=true;actionDialog=false}
            MenuRow("⌫","Bu sayfayı temizle"){strokes=emptyList();notes=emptyList();savePage();actionDialog=false}
            MenuRow("⇧","PDF dışa aktar"){pdfLauncher.launch("${selected!!.title.replace(Regex("[^A-Za-z0-9_-]"),"_")}.pdf");actionDialog=false}
            MenuRow("✎","Defteri yeniden adlandır"){titleInput=selected!!.title;renameTarget=selected;renameDialog=true;actionDialog=false}
        })
        deleteTarget?.takeIf { !renameDialog }?.let { target -> PaperDialog("Defteri sil?", "‘${target.title}’ kitabı kitaplığından kaldırılacak.", {deleteTarget=null}, {Row(horizontalArrangement=Arrangement.End,modifier=Modifier.fillMaxWidth()){ActionButton("×","Vazgeç",false){deleteTarget=null};Spacer(Modifier.width(8.dp));ActionButton("⌫","Defteri sil",true){saveBooks(books.filterNot{it.id==target.id});prefs.edit().remove("count_${target.id}").apply();deleteTarget=null}}}) }
    }
}

@Composable private fun LibraryScreen(books:List<Book>,query:String,onQuery:(String)->Unit,dark:Boolean,onTheme:()->Unit,onCreate:()->Unit,onOpen:(Book)->Unit,onFavorite:(Book)->Unit,onRename:(Book)->Unit,onDelete:(Book)->Unit){
    val fg=if(dark)Color(0xFFF0EEE7)else Ink;val surface=if(dark)Color(0xFF242C27)else Color(0xFFEEEAE1)
    Row(Modifier.fillMaxSize()){
        Column(Modifier.width(86.dp).fillMaxHeight().background(surface).padding(vertical=25.dp),horizontalAlignment=Alignment.CenterHorizontally){Box(Modifier.size(42.dp).clip(RoundedCornerShape(15.dp)).background(Moss),contentAlignment=Alignment.Center){Text("p.",color=Color.White,fontSize=22.sp,fontWeight=FontWeight.Bold)};Spacer(Modifier.height(42.dp));RailItem("▦","Kitaplık",true,dark){};RailItem("☆","Favori",false,dark){onQuery("#favorite")};Spacer(Modifier.weight(1f));RailItem(if(dark)"☼" else "◐","Tema",false,dark){onTheme()};Text("PAPERTRAIL",Modifier.padding(top=20.dp),color=Muted,fontSize=7.sp,letterSpacing=1.2.sp)
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal=38.dp,vertical=28.dp)){
            val columns=(maxWidth/232.dp).toInt().coerceIn(2,4);val gap=20.dp;val cardWidth=(maxWidth-gap*(columns-1).toFloat())/columns.toFloat()
            Column(Modifier.fillMaxSize()){
                Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("KİTAPLIĞIN",color=Moss,fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=2.sp);Spacer(Modifier.height(7.dp));Text("Yaratıcı alanın.",color=fg,fontSize=30.sp,fontWeight=FontWeight.SemiBold,fontFamily=FontFamily.Serif);Text("Fikirlerin için sessiz bir yer.",Modifier.padding(top=4.dp),color=Muted,fontSize=13.sp)};SearchField(query.removePrefix("#favorite"),onQuery,"Defterlerinde ara");Spacer(Modifier.width(14.dp));ActionButton("◐",if(dark)"Açık görünüm" else "Gece görünümü",false,onClick=onTheme);Spacer(Modifier.width(10.dp));ActionButton("+","Defter oluştur",true,onClick=onCreate)}
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth().height(90.dp).clip(RoundedCornerShape(20.dp)).background(if(dark)Color(0xFF2A352D) else Color(0xFFE9E8DD)).padding(horizontal=24.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("BUGÜNÜN NOTU",color=Moss,fontSize=9.sp,fontWeight=FontWeight.Bold,letterSpacing=1.7.sp);Text(if(books.isEmpty())"Yeni bir başlangıç." else "Her fikir bir yere ait.",Modifier.padding(top=5.dp),color=fg,fontSize=19.sp,fontWeight=FontWeight.Medium,fontFamily=FontFamily.Serif)};Text("${books.size.toString().padStart(2,'0')}",color=Moss.copy(alpha=.75f),fontSize=32.sp,fontWeight=FontWeight.Light);Text("  DEFTER",color=Muted,fontSize=9.sp,letterSpacing=1.sp)}
                Spacer(Modifier.height(26.dp));Row(verticalAlignment=Alignment.CenterVertically){Text(if(query=="#favorite")"Favorilerin" else "Rafında",color=fg,fontSize=18.sp,fontWeight=FontWeight.SemiBold);Spacer(Modifier.width(10.dp));Text("${books.size} defter",color=Muted,fontSize=11.sp)};Spacer(Modifier.height(15.dp))
                val filtered=books
                if(filtered.isEmpty())Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally){Text("✳",color=Moss,fontSize=34.sp);Spacer(Modifier.height(12.dp));Text(if(query.isBlank())"Rafın yeni fikirleri bekliyor." else "Burada henüz bir şey yok.",color=fg,fontSize=17.sp,fontFamily=FontFamily.Serif);Spacer(Modifier.height(10.dp));ActionButton("+","İlk defterini oluştur",true,onClick=onCreate)}}
                else LazyColumn(verticalArrangement=Arrangement.spacedBy(22.dp)){items(filtered.chunked(columns)){rowBooks->Row(horizontalArrangement=Arrangement.spacedBy(gap),modifier=Modifier.fillMaxWidth()){rowBooks.forEach{book->NotebookCover(book,dark,Modifier.width(cardWidth),onOpen,onFavorite,onRename,onDelete)}}}}
            }
        }
    }
}

@Composable private fun NotebookCover(book:Book,dark:Boolean,modifier:Modifier,onOpen:()->Unit,onFavorite:(Book)->Unit,onRename:(Book)->Unit,onDelete:(Book)->Unit){
    var menu by remember{mutableStateOf(false)}
    Column(modifier){
        Box(Modifier.fillMaxWidth().height(206.dp).shadow(9.dp,RoundedCornerShape(12.dp),ambientColor=Color.Black.copy(alpha=.1f)).clip(RoundedCornerShape(12.dp)).background(Color(book.color.toInt())).clickable(onClick=onOpen)){
            Canvas(Modifier.fillMaxSize()){val w=size.width;val h=size.height;drawRect(Color.Black.copy(alpha=.06f));drawLine(Color.White.copy(alpha=.22f),Offset(24f,0f),Offset(24f,h),1.2f);drawLine(Color.Black.copy(alpha=.14f),Offset(30f,0f),Offset(30f,h),1f);drawCircle(Color.White.copy(alpha=.08f),w*.34f,Offset(w*.77f,h*.2f));drawCircle(Color.White.copy(alpha=.07f),w*.2f,Offset(w*.85f,h*.83f));val path=Path().apply{moveTo(w*.2f,h*.74f);cubicTo(w*.45f,h*.55f,w*.63f,h*.94f,w*.95f,h*.63f)};drawPath(path,Color.White.copy(alpha=.23f),style=Stroke(1.3f))}
            Column(Modifier.align(Alignment.Center).padding(start=26.dp,end=15.dp),horizontalAlignment=Alignment.CenterHorizontally){Text("✳",color=Color.White.copy(alpha=.87f),fontSize=23.sp);Spacer(Modifier.height(12.dp));Text(book.title,color=Color.White,fontSize=17.sp,fontWeight=FontWeight.SemiBold,fontFamily=FontFamily.Serif,maxLines=2);Spacer(Modifier.height(7.dp));Text("FIELD NOTES  ·  01",color=Color.White.copy(alpha=.74f),fontSize=8.sp,letterSpacing=1.6.sp)}
            Box(Modifier.align(Alignment.TopEnd).padding(9.dp).size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha=.12f)).clickable{onFavorite(book)},contentAlignment=Alignment.Center){Text(if(book.favorite)"♥" else "♡",color=Color.White,fontSize=16.sp)}
        }
        Row(Modifier.fillMaxWidth().padding(top=10.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f).clickable(onClick=onOpen)){Text(book.title,color=if(dark)Color(0xFFF0EEE7)else Ink,fontSize=13.sp,fontWeight=FontWeight.SemiBold);Text("Düzenlendi · ${book.updated}",Modifier.padding(top=3.dp),color=Muted,fontSize=10.sp)};Box(Modifier.clip(CircleShape).clickable{menu=true}.padding(horizontal=9.dp,vertical=4.dp)){Text("···",color=Muted,fontSize=17.sp)} }
    }
    if(menu)PaperDialog(book.title,"Defter seçenekleri",{menu=false},{MenuRow("✎","Yeniden adlandır"){menu=false;onRename(book)};MenuRow("♡",if(book.favorite)"Favoriden çıkar" else "Favorilere ekle"){menu=false;onFavorite(book)};MenuRow("⌫","Defteri sil"){menu=false;onDelete(book)}})
}

@Composable private fun EditorHeader(book:Book,page:Int,count:Int,loading:Boolean,onBack:()->Unit,onPrevious:()->Unit,onNext:()->Unit,onTemplates:()->Unit,onMore:()->Unit){
    Row(Modifier.fillMaxWidth().height(48.dp).background(Color(0xFF28558E)).padding(horizontal=11.dp),verticalAlignment=Alignment.CenterVertically){
            HeaderGlyph("‹",onBack);HeaderGlyph("▦",onTemplates);HeaderGlyph("⌕",onMore)
            Spacer(Modifier.weight(1f));Text(book.title + if(loading) "  ·" else "",color=Color.White,fontSize=13.sp,fontWeight=FontWeight.SemiBold);Text("  ⌄",color=Color.White.copy(alpha=.8f),fontSize=12.sp);Spacer(Modifier.weight(1f))
            HeaderGlyph("‹",onPrevious);Text("$page / $count",color=Color.White,fontSize=9.sp);HeaderGlyph("›",onNext);HeaderGlyph("♧",onMore);HeaderGlyph("⇧",onMore);HeaderGlyph("···",onMore)
    }
}

@Composable private fun HeaderGlyph(glyph:String,onClick:()->Unit){Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).clickable(onClick=onClick),contentAlignment=Alignment.Center){Text(glyph,color=Color.White,fontSize=19.sp,fontWeight=FontWeight.Light)}}

@Composable private fun EditorToolbar(tool:Tool,color:Color,width:Float,dark:Boolean,onUndo:()->Unit,onRedo:()->Unit,onTool:(Tool)->Unit,onColor:(Color)->Unit,onWidth:(Float)->Unit,onAddPage:()->Unit){
    val toolbarBg=if(dark)Color(0xFF242C27)else Color(0xFFF4F4F3)
    val availableColors=listOf(Color(0xFF252525),Color(0xFF263E32),Color(0xFF386BB7),Color(0xFFE45665),Color(0xFFE3A84A),Color(0xFF8D61A9))
    Row(Modifier.fillMaxWidth().height(48.dp).background(toolbarBg).border(width=0.5.dp,color=if(dark)Color(0xFF3A443D)else Color(0xFFDDDEDF)).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
        Row(Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically){
            RibbonControl("↶",false,dark,onUndo)
            RibbonControl("↷",false,dark,onRedo)
            RibbonSeparator(dark)
            Tool.entries.forEach{item->RibbonControl(item.glyph,tool==item,dark){onTool(item)}}
            RibbonSeparator(dark)
            availableColors.forEach{c->Box(Modifier.padding(horizontal=5.dp).size(18.dp).clip(CircleShape).background(c).then(if(c==color)Modifier.border(2.dp,Color.White,CircleShape).border(1.dp,Color(0xFF687682),CircleShape)else Modifier).clickable{onColor(c)})}
            RibbonSeparator(dark)
            Text("−",Modifier.padding(horizontal=6.dp),color=if(dark)Color.White else Ink,fontSize=12.sp)
            MiniSlider(width,onWidth,1f..12f,Modifier.width(100.dp))
            Text("+",Modifier.padding(horizontal=6.dp),color=if(dark)Color.White else Ink,fontSize=13.sp)
            RibbonControl("▤",false,dark,onAddPage)
        }
        RibbonSeparator(dark)
        Text("${width.toInt()} px",Modifier.padding(horizontal=5.dp),color=if(dark)Color.White else Muted,fontSize=9.sp)
    }
}
@Composable private fun RibbonControl(glyph:String,active:Boolean,dark:Boolean,onClick:()->Unit){Column(Modifier.padding(horizontal=2.dp).fillMaxHeight().width(36.dp).clip(RoundedCornerShape(7.dp)).background(if(active)if(dark)Color(0xFF3C4B40)else Color(0xFFE0E8F0)else Color.Transparent).clickable(onClick=onClick),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Text(glyph,color=if(active)Color(0xFF2864A3)else if(dark)Color(0xFFE8E7E2)else Color(0xFF555B62),fontSize=17.sp);if(active)Box(Modifier.padding(top=2.dp).width(14.dp).height(2.dp).background(Color(0xFF3779B8),CircleShape))}}
@Composable private fun RibbonSeparator(dark:Boolean){Box(Modifier.padding(horizontal=7.dp).width(1.dp).height(21.dp).background(if(dark)Color(0xFF465047)else Color(0xFFD6D8DA)))}

@Composable private fun ToolTile(tool:Tool,active:Boolean,dark:Boolean,onClick:()->Unit){val fg=if(dark)Color(0xFFEAE8E0)else Ink;Column(Modifier.width(64.dp).padding(vertical=3.dp).clip(RoundedCornerShape(15.dp)).background(if(active){if(dark)Color(0xFF3D4B40)else Color(0xFFDCE5DA)}else Color.Transparent).clickable(onClick=onClick).padding(vertical=7.dp),horizontalAlignment=Alignment.CenterHorizontally){Text(tool.glyph,color=if(active)Moss else fg.copy(alpha=.72f),fontSize=20.sp,fontWeight=FontWeight.Medium);Text(tool.label,color=if(active)Moss else Muted,fontSize=8.sp,maxLines=1)} }
private fun prepareThumbnailStroke(stroke:StrokeData):RenderedStroke{val stride=((stroke.points.size+79)/80).coerceAtLeast(1);if(stride==1)return prepareStroke(stroke);val indexes=stroke.points.indices.filter{it%stride==0||it==stroke.points.lastIndex};return prepareStroke(stroke.copy(points=indexes.map{stroke.points[it]},pressures=indexes.mapNotNull{stroke.pressures.getOrNull(it)}))}
@Composable private fun PageThumbnail(bookId:Long,page:Int,active:Boolean,dark:Boolean,prefs:android.content.SharedPreferences,revision:Int,onClick:()->Unit){val previews=remember(bookId,page,revision){loadStrokes(prefs,bookId,page).map(::prepareThumbnailStroke)};Column(Modifier.fillMaxWidth().clickable(onClick=onClick)){Box(Modifier.fillMaxWidth().height(120.dp).shadow(if(active)5.dp else 1.dp,RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)).background(Color(0xFFFFFEFA)).border(if(active)2.dp else 1.dp,if(active)Moss else Line,RoundedCornerShape(8.dp))){Canvas(Modifier.fillMaxSize().padding(8.dp)){for(y in 15..size.height.toInt() step 13)drawLine(Color(0xFFE8E5DD),Offset(0f,y.toFloat()),Offset(size.width,y.toFloat()),.45f);val maxX=previews.flatMap{it.stroke.points}.maxOfOrNull{it.x}?:900f;val maxY=previews.flatMap{it.stroke.points}.maxOfOrNull{it.y}?:1200f;val scale=min((size.width-8f)/maxX,(size.height-8f)/maxY).coerceAtMost(.24f);drawContext.transform.scale(scale,scale,pivot=Offset.Zero);previews.forEach(::drawRenderedStroke)};if(previews.isEmpty())Text("$page",Modifier.align(Alignment.BottomEnd).padding(7.dp),color=Muted,fontSize=9.sp)};Text("Sayfa $page",Modifier.align(Alignment.CenterHorizontally).padding(top=5.dp),color=if(active)Moss else Muted,fontSize=9.sp)} }

@Composable private fun RailItem(glyph:String,label:String,active:Boolean,dark:Boolean,onClick:()->Unit){Column(Modifier.padding(horizontal=8.dp,vertical=4.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if(active){if(dark)Color(0xFF354139)else Color(0xFFDCE5DA)}else Color.Transparent).clickable(onClick=onClick).padding(vertical=10.dp),horizontalAlignment=Alignment.CenterHorizontally){Text(glyph,color=if(active)Moss else Muted,fontSize=19.sp);Text(label,color=if(active)Moss else Muted,fontSize=8.sp,modifier=Modifier.padding(top=4.dp))}}

@Composable private fun SearchField(value:String,onValue:(String)->Unit,placeholder:String){Row(Modifier.width(245.dp).height(42.dp).clip(RoundedCornerShape(15.dp)).background(Color.White.copy(alpha=.75f)).border(1.dp,Line,RoundedCornerShape(15.dp)).padding(horizontal=13.dp),verticalAlignment=Alignment.CenterVertically){Text("⌕",color=Muted,fontSize=21.sp);Spacer(Modifier.width(8.dp));BasicTextField(value,onValue,Modifier.weight(1f),singleLine=true,textStyle=TextStyle(color=Ink,fontSize=12.sp,fontFamily=FontFamily.SansSerif),decorationBox={inner->Box{if(value.isBlank())Text(placeholder,color=Muted,fontSize=12.sp);inner()}})}}
@Composable private fun EntryField(value:String,onValue:(String)->Unit,hint:String,multiline:Boolean=false){BasicTextField(value,onValue,Modifier.fillMaxWidth().heightIn(min=48.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFFF3F0E9)).padding(horizontal=14.dp,vertical=13.dp),singleLine=!multiline,textStyle=TextStyle(color=Ink,fontSize=14.sp,fontFamily=FontFamily.SansSerif),keyboardOptions=KeyboardOptions(imeAction=if(multiline)ImeAction.Default else ImeAction.Done),decorationBox={inner->Box{if(value.isBlank())Text(hint,color=Muted,fontSize=13.sp);inner()}})}

@Composable private fun MiniSlider(value:Float,onValue:(Float)->Unit,range:ClosedFloatingPointRange<Float>,modifier:Modifier=Modifier){var widthPx by remember{mutableFloatStateOf(1f)};Canvas(modifier.height(28.dp).pointerInput(range){detectDragGestures(onDragStart={p->onValue(range.start+(p.x/widthPx).coerceIn(0f,1f)*(range.endInclusive-range.start))},onDrag={c,_->c.consume();onValue(range.start+(c.position.x/widthPx).coerceIn(0f,1f)*(range.endInclusive-range.start))})}.pointerInput(range){detectTapGestures{p->onValue(range.start+(p.x/widthPx).coerceIn(0f,1f)*(range.endInclusive-range.start))}}){widthPx=size.width;val fraction=((value-range.start)/(range.endInclusive-range.start)).coerceIn(0f,1f);val cy=size.height/2;drawLine(Color(0xFFD6D2C9),Offset(0f,cy),Offset(size.width,cy),3f,cap=StrokeCap.Round);drawLine(Moss,Offset(0f,cy),Offset(size.width*fraction,cy),3f,cap=StrokeCap.Round);drawCircle(Color.White,7f,Offset(size.width*fraction,cy));drawCircle(Moss,5f,Offset(size.width*fraction,cy))}}

@Composable private fun TogglePill(value:Boolean,onChange:(Boolean)->Unit){Box(Modifier.width(42.dp).height(24.dp).clip(CircleShape).background(if(value)Moss else Color(0xFFD4D1C9)).clickable{onChange(!value)}.padding(3.dp),contentAlignment=if(value)Alignment.CenterEnd else Alignment.CenterStart){Box(Modifier.size(18.dp).clip(CircleShape).background(Color.White))}}
@Composable private fun ActionButton(glyph:String,label:String,primary:Boolean,modifier:Modifier=Modifier,onClick:()->Unit){Row(modifier.height(40.dp).clip(RoundedCornerShape(13.dp)).background(if(primary)Moss else Color(0xFFEAE7DF)).clickable(onClick=onClick).padding(horizontal=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center){Text(glyph,color=if(primary)Color.White else Moss,fontSize=15.sp,fontWeight=FontWeight.SemiBold);if(label.isNotBlank()){Spacer(Modifier.width(8.dp));Text(label,color=if(primary)Color.White else Ink,fontSize=11.sp,fontWeight=FontWeight.SemiBold,maxLines=1)}}}
@Composable private fun GlyphControl(glyph:String,onClick:()->Unit){Box(Modifier.size(38.dp).clip(CircleShape).clickable(onClick=onClick),contentAlignment=Alignment.Center){Text(glyph,color=Ink,fontSize=21.sp,fontWeight=FontWeight.Light)}}
@Composable private fun MenuRow(glyph:String,title:String,onClick:()->Unit){Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick=onClick).padding(horizontal=12.dp,vertical=13.dp),verticalAlignment=Alignment.CenterVertically){Text(glyph,color=Moss,fontSize=18.sp);Spacer(Modifier.width(13.dp));Text(title,color=Ink,fontSize=13.sp)}}
@Composable private fun PaperDialog(title:String,subtitle:String,onDismiss:()->Unit,content:@Composable ColumnScope.()->Unit){Dialog(onDismissRequest=onDismiss){Column(Modifier.fillMaxWidth().widthIn(max=440.dp).shadow(24.dp,RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(Color(0xFFFAF8F3)).padding(24.dp)){Text(title,color=Ink,fontSize=21.sp,fontWeight=FontWeight.SemiBold,fontFamily=FontFamily.Serif);Text(subtitle,Modifier.padding(top=5.dp,bottom=20.dp),color=Muted,fontSize=12.sp);content()}}}

internal data class RenderedStroke(val stroke:StrokeData,val path:Path,val filled:Boolean)
internal fun prepareStroke(stroke:StrokeData):RenderedStroke {
    if(stroke.points.isEmpty())return RenderedStroke(stroke,Path(),true)
    val first=stroke.points.first()
    if(stroke.kind in setOf("line","rect","circle")) {
        val path=Path().apply { moveTo(first.x,first.y); if(stroke.kind=="line")stroke.points.lastOrNull()?.let{lineTo(it.x,it.y)}else{val e=stroke.points.last();val r=Rect(minOf(first.x,e.x),minOf(first.y,e.y),maxOf(first.x,e.x),maxOf(first.y,e.y));if(stroke.kind=="rect")addRect(r)else addOval(r)} }
        return RenderedStroke(stroke,path,false)
    }
    val points=stroke.points.mapIndexed{i,p->InkSample(p,stroke.pressures.getOrElse(i){.52f})}
    return RenderedStroke(stroke,InkEngine.outline(InkEngine.smooth(points,2),stroke.width),true)
}
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRenderedStroke(rendered:RenderedStroke){
    if(rendered.stroke.points.isEmpty())return
    if(rendered.filled)drawPath(rendered.path,rendered.stroke.color) else drawPath(rendered.path,rendered.stroke.color,style=Stroke(rendered.stroke.width,cap=StrokeCap.Round))
}
internal fun StrokeData.bounds():Rect{val xs=points.map{it.x};val ys=points.map{it.y};return Rect(xs.minOrNull()?:0f,ys.minOrNull()?:0f,xs.maxOrNull()?:0f,ys.maxOrNull()?:0f)}

private fun loadBooks(p:android.content.SharedPreferences):List<Book>{val s=p.getString("books",null)?:return listOf(Book(1,"Günlük",0xFF55735B,"Bugün"),Book(2,"Ders notları",0xFF64799D,"Dün"),Book(3,"Fikir defteri",0xFFB47762,"2 gün önce"));return try{val a=JSONArray(s);(0 until a.length()).map{val o=a.getJSONObject(it);Book(o.getLong("id"),o.getString("title"),o.getLong("color"),o.getString("updated"),o.optBoolean("favorite"))}}catch(_:Exception){emptyList()}}
private fun booksToJson(books:List<Book>)=JSONArray().apply{books.forEach{put(JSONObject().put("id",it.id).put("title",it.title).put("color",it.color).put("updated",it.updated).put("favorite",it.favorite))}}.toString()
private fun saveStrokes(p:android.content.SharedPreferences,id:Long,page:Int,strokes:List<StrokeData>){val a=JSONArray();strokes.forEach{s->val pts=JSONArray();s.points.forEach{pts.put(JSONArray().put(it.x).put(it.y))};val pressure=JSONArray();s.pressures.forEach{pressure.put(it)};a.put(JSONObject().put("points",pts).put("pressure",pressure).put("color",s.color.value.toLong()).put("width",s.width).put("kind",s.kind))};p.edit().putString("strokes_${id}_$page",a.toString()).apply()}
private fun loadStrokes(p:android.content.SharedPreferences,id:Long,page:Int):List<StrokeData> = try{val a=JSONArray(p.getString("strokes_${id}_$page","[]"));(0 until a.length()).map{val o=a.getJSONObject(it);val pts=o.getJSONArray("points");StrokeData((0 until pts.length()).map{i->Offset(pts.getJSONArray(i).getDouble(0).toFloat(),pts.getJSONArray(i).getDouble(1).toFloat())},Color(o.getLong("color").toULong().toLong()),o.getDouble("width").toFloat(),o.optString("kind","pen"),o.optJSONArray("pressure")?.let{x->(0 until x.length()).map{x.getDouble(it).toFloat()}}?:emptyList())}}catch(_:Exception){emptyList()}
private fun saveNotes(p:android.content.SharedPreferences,id:Long,page:Int,notes:List<NoteText>){val a=JSONArray();notes.forEach{a.put(JSONObject().put("text",it.text).put("x",it.x).put("y",it.y).put("color",it.color.value.toLong()).put("size",it.size))};p.edit().putString("notes_${id}_$page",a.toString()).apply()}
private fun loadNotes(p:android.content.SharedPreferences,id:Long,page:Int):List<NoteText> = try{val a=JSONArray(p.getString("notes_${id}_$page","[]"));(0 until a.length()).map{val o=a.getJSONObject(it);NoteText(o.getString("text"),o.getDouble("x").toFloat(),o.getDouble("y").toFloat(),Color(o.getLong("color").toULong().toLong()),o.getDouble("size").toFloat())}}catch(_:Exception){emptyList()}
private fun exportPdfToUri(context:android.content.Context,uri:android.net.Uri,book:Book,pages:Int,style:Int){try{val pdf=android.graphics.pdf.PdfDocument();for(n in 1..pages){val info=android.graphics.pdf.PdfDocument.PageInfo.Builder(1200,1600,n).create();val page=pdf.startPage(info);val c=page.canvas;val paint=NativePaint(NativePaint.ANTI_ALIAS_FLAG);paint.color=android.graphics.Color.rgb(255,254,250);c.drawColor(paint.color);paint.color=android.graphics.Color.rgb(220,220,214);if(style==0||style==2)for(y in 140..1550 step 55)c.drawLine(70f,y.toFloat(),1130f,y.toFloat(),paint);if(style==1){for(y in 140..1550 step 55)c.drawLine(70f,y.toFloat(),1130f,y.toFloat(),paint);for(x in 70..1130 step 55)c.drawLine(x.toFloat(),120f,x.toFloat(),1550f,paint)};if(style==2)c.drawLine(160f,120f,160f,1550f,paint);paint.color=android.graphics.Color.rgb(55,75,60);paint.textSize=36f;c.drawText(book.title,75f,80f,paint);val lines=loadStrokes(context.getSharedPreferences("papertrail",0),book.id,n);lines.forEach{s->paint.color=s.color.toArgb();paint.strokeCap=NativePaint.Cap.ROUND;paint.style=NativePaint.Style.STROKE;val samples=InkEngine.smooth(s.points.mapIndexed{i,p->InkSample(p,s.pressures.getOrElse(i){.52f})},6);if(samples.size>1)samples.zipWithNext().forEachIndexed{i,(a,b)->paint.strokeWidth=(InkEngine.width(s.width,a.pressure,i,samples.size)+InkEngine.width(s.width,b.pressure,i+1,samples.size))/2f;c.drawLine(a.position.x,a.position.y,b.position.x,b.position.y,paint)}else samples.firstOrNull()?.let{paint.style=NativePaint.Style.FILL;c.drawCircle(it.position.x,it.position.y,InkEngine.width(s.width,it.pressure,0,1)/2f,paint)}};loadNotes(context.getSharedPreferences("papertrail",0),book.id,n).forEach{paint.style=NativePaint.Style.FILL;paint.color=it.color.toArgb();paint.textSize=it.size;c.drawText(it.text,it.x,it.y,paint)};pdf.finishPage(page)};context.contentResolver.openOutputStream(uri)?.use{pdf.writeTo(it)}?:error("Dosya açılamadı");pdf.close();android.widget.Toast.makeText(context,"${pages} sayfalı PDF dışa aktarıldı",android.widget.Toast.LENGTH_LONG).show()}catch(e:Exception){android.widget.Toast.makeText(context,"PDF oluşturulamadı: ${e.message}",android.widget.Toast.LENGTH_LONG).show()}}
