package com.papertrail.notebook

import android.graphics.Paint as NativePaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

@Stable internal class StrokeFrame(val items: List<StrokeData>)
@Stable internal class NoteFrame(val items: List<NoteText>)
@Stable internal class SelectionFrame(val rect: Rect?, val indexes: Set<Int>)

@Composable
internal fun DrawingSurface(
    modifier: Modifier, dark: Boolean, pageStyle: Int, tool: Tool, color: Color,
    width: Float, stabilization: Float, stylusOnly: Boolean,
    strokes: List<StrokeData>, notes: List<NoteText>, selection: Rect?, selectedIndexes: Set<Int>, enabled: Boolean,
    zoomState: MutableFloatState, panState: MutableState<Offset>,
    onCommit: (StrokeData) -> Unit, onMove: (List<StrokeData>, Rect?) -> Unit,
    onSelect: (Rect, Set<Int>) -> Unit, onText: (Offset) -> Unit, onZoomSettled: (Float) -> Unit,
) {
    // Pointer-frequency state is intentionally isolated from the library, toolbar and page rail.
    var draft by remember { mutableStateOf<StrokeData?>(null) }
    var lastSampleTime by remember { mutableLongStateOf(0L) }
    val strokeFrame = remember(strokes) { StrokeFrame(strokes) }
    val noteFrame = remember(notes) { NoteFrame(notes) }
    val selectionFrame = remember(selection, selectedIndexes) { SelectionFrame(selection, selectedIndexes) }
    val pageModifier = remember(dark, pageStyle, zoomState, panState) {
        Modifier.fillMaxSize().padding(horizontal = 34.dp, vertical = 20.dp).graphicsLayer {
            scaleX = zoomState.floatValue; scaleY = zoomState.floatValue
            translationX = panState.value.x; translationY = panState.value.y
        }
    }
    val preparedDraft = remember(draft) { draft?.takeIf { it.kind != "lasso" && it.kind != "move" }?.let(::prepareStroke) }

    Box(modifier.background(if (dark) Color(0xFF303833) else Color(0xFFE4E1DA))) {
        PagePaperLayer(pageModifier, dark, pageStyle)
        SavedInkLayer(pageModifier, strokeFrame, noteFrame, selectionFrame)
        Canvas(pageModifier
            .pointerInput(enabled, tool, color, width, strokes, stabilization, stylusOnly) {
                if (enabled) detectDragGestures(onDragStart = { position ->
                    lastSampleTime = 0L
                    if (tool == Tool.LASSO) {
                        draft = StrokeData(listOf(position), Color(0xFF55735B), 1.5f, if (selection?.contains(position) == true) "move" else "lasso")
                    } else if (tool != Tool.TEXT) {
                        val ink = when (tool) {
                            Tool.ERASER -> if (dark) Color(0xFF2B332E) else Color(0xFFFFFEFA)
                            Tool.HIGHLIGHTER -> color.copy(alpha = .30f)
                            else -> color
                        }
                        draft = StrokeData(listOf(position), ink, if (tool == Tool.HIGHLIGHTER) width * 4f else width,
                            when (tool) { Tool.HIGHLIGHTER -> "highlight"; Tool.ERASER -> "erase"; Tool.LINE -> "line"; Tool.RECTANGLE -> "rect"; Tool.CIRCLE -> "circle"; else -> "pen" }, listOf(.52f))
                    }
                }, onDrag = { change, _ ->
                    if (!stylusOnly || change.type == PointerType.Stylus || change.type == PointerType.Eraser) {
                        change.consume()
                        draft?.let { current ->
                            val previous=current.points.last()
                            val elapsed=if(lastSampleTime==0L)16f else (change.uptimeMillis-lastSampleTime).coerceAtLeast(1L).toFloat()
                            val dx=change.position.x-previous.x;val dy=change.position.y-previous.y
                            val speed=sqrt(dx*dx+dy*dy)/elapsed
                            val smoothing=if(tool==Tool.PEN||tool==Tool.HIGHLIGHTER)stabilization else 0f
                            val next=InkEngine.stabilize(previous,change.position,smoothing,speed)
                            lastSampleTime=change.uptimeMillis
                            val sensed=if(change.type==PointerType.Stylus||change.type==PointerType.Eraser)change.pressure.coerceIn(.08f,1f)else .52f
                            val previousPressure=current.pressures.lastOrNull()?:.52f
                            val filteredPressure=previousPressure+(sensed-previousPressure)*.22f
                            draft=current.copy(points=current.points+next,pressures=current.pressures+filteredPressure)
                        }
                    } else draft=null
                }, onDragEnd = {
                    draft?.let { stroke -> when {
                        stroke.kind=="move"&&stroke.points.size>1->{val dx=stroke.points.last().x-stroke.points.first().x;val dy=stroke.points.last().y-stroke.points.first().y;val moved=strokes.mapIndexed{i,item->if(i in selectedIndexes)item.copy(points=item.points.map{Offset(it.x+dx,it.y+dy)})else item};onMove(moved,selection?.let{Rect(it.left+dx,it.top+dy,it.right+dx,it.bottom+dy)})}
                        stroke.kind=="lasso"->{val xs=stroke.points.map{it.x};val ys=stroke.points.map{it.y};val rect=Rect(xs.minOrNull()?:0f,ys.minOrNull()?:0f,xs.maxOrNull()?:0f,ys.maxOrNull()?:0f);onSelect(rect,strokes.indices.filter{i->strokes[i].points.any{rect.contains(it)}}.toSet())}
                        else->onCommit(stroke)
                    } };draft=null
                })
            }
            .pointerInput(tool, enabled) { if(enabled)detectTapGestures{p->if(tool==Tool.TEXT)onText(p)} }
            .pointerInput(enabled) {
                if(enabled)awaitEachGesture {
                    awaitFirstDown(requireUnconsumed=false)
                    var event=awaitPointerEvent()
                    while(event.changes.count{it.pressed}<2){if(event.changes.none{it.pressed})return@awaitEachGesture;event=awaitPointerEvent()}
                    fun centroid(points:List<Offset>)=Offset(points.sumOf{it.x.toDouble()}.toFloat()/points.size,points.sumOf{it.y.toDouble()}.toFloat()/points.size)
                    fun spread(points:List<Offset>,center:Offset)=points.map{sqrt((it.x-center.x)*(it.x-center.x)+(it.y-center.y)*(it.y-center.y))}.average().toFloat()
                    var old=event.changes.filter{it.pressed}.map{it.position}
                    while(event.changes.count{it.pressed}>=2){val active=event.changes.filter{it.pressed};val now=active.map{it.position};if(old.size>=2&&now.size>=2){val c0=centroid(old);val c1=centroid(now);val d0=spread(old,c0);val d1=spread(now,c1);panState.value+=c1-c0;if(d0>1f)zoomState.floatValue=(zoomState.floatValue*d1/d0).coerceIn(.55f,3f);active.forEach{it.consume()}};old=now;event=awaitPointerEvent()}
                    onZoomSettled(zoomState.floatValue)
                }
            }) {
            preparedDraft?.let(::drawRenderedStroke)
        }
    }
}

@Composable
private fun PagePaperLayer(modifier: Modifier, dark: Boolean, pageStyle: Int) {
    Canvas(modifier) {
        drawRect(if(dark)Color(0xFF2B332E)else Color(0xFFFFFEFA))
        val step=34.dp.toPx()
        when(pageStyle){
            0->{var y=step;while(y<size.height){drawLine(if(dark)Color(0xFF3B443D)else Color(0xFFE8E5DD),Offset(0f,y),Offset(size.width,y),1f);y+=step}}
            1->{var x=step;var y=step;while(y<size.height){drawLine(Color(0xFFE8E5DD),Offset(0f,y),Offset(size.width,y),1f);y+=step};while(x<size.width){drawLine(Color(0xFFE8E5DD),Offset(x,0f),Offset(x,size.height),1f);x+=step}}
            2->{var y=step;while(y<size.height){drawLine(Color(0xFFE8E5DD),Offset(0f,y),Offset(size.width,y),1f);y+=step};drawLine(Color(0xFFE7B9B0),Offset(step*2,0f),Offset(step*2,size.height),1.2f)}
        }
    }
}

@Composable
private fun SavedInkLayer(modifier: Modifier, strokeFrame: StrokeFrame, noteFrame: NoteFrame, selection: SelectionFrame) {
    val cache=remember{java.util.IdentityHashMap<StrokeData,RenderedStroke>()}
    val paths=remember(strokeFrame){strokeFrame.items.map{stroke->cache[stroke]?:prepareStroke(stroke).also{if(cache.size>700)cache.clear();cache[stroke]=it}}}
    Canvas(modifier){
        paths.forEachIndexed{i,path->drawRenderedStroke(path);if(i in selection.indexes){val b=path.stroke.bounds();drawRect(Color(0xFF55735B).copy(alpha=.28f),Offset(b.left,b.top),androidx.compose.ui.geometry.Size(b.width,b.height),style=Stroke(2f))}}
        selection.rect?.let{drawRect(Color(0xFF55735B).copy(alpha=.65f),Offset(it.left,it.top),androidx.compose.ui.geometry.Size(it.width,it.height),style=Stroke(1.8f))}
        noteFrame.items.forEach{note->drawContext.canvas.nativeCanvas.drawText(note.text,note.x,note.y,NativePaint().apply{color=note.color.toArgb();textSize=note.size;isAntiAlias=true})}
    }
}
