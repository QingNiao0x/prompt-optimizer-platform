"""补充原生 DrawingML 渐变、柔化边缘、嵌入字体与母版设计备注。"""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
from copy import deepcopy
import json, xml.etree.ElementTree as E

OUT=Path(__file__).resolve().parents[1]
TARGET=OUT/'Prompt-Optimizer-用户介绍.pptx'
N={'p':'http://schemas.openxmlformats.org/presentationml/2006/main','a':'http://schemas.openxmlformats.org/drawingml/2006/main','r':'http://schemas.openxmlformats.org/officeDocument/2006/relationships'}
for prefix,uri in N.items():E.register_namespace(prefix,uri)
def q(prefix,local):return '{'+N[prefix]+'}'+local
def child(parent,prefix,local,attrs=None):return E.SubElement(parent,q(prefix,local),attrs or {})
def xml(root):return E.tostring(root,encoding='utf-8',xml_declaration=True)
def gradient(colors,angle=0):
    g=E.Element(q('a','gradFill'),{'rotWithShape':'1'})
    stops=child(g,'a','gsLst')
    for i,color in enumerate(colors):
        st=child(stops,'a','gs',{'pos':str(round(i/(len(colors)-1)*100000))})
        child(st,'a','srgbClr',{'val':color.upper()})
    child(g,'a','lin',{'ang':str(angle),'scaled':'1'})
    return g
def replace_fill(parent,fill):
    old=next((c for c in parent if c.tag in {q('a',n) for n in ['solidFill','noFill','gradFill','pattFill']}),None)
    if old is not None:
        idx=list(parent).index(old);parent.remove(old);parent.insert(idx,fill)
    else:parent.append(fill)
layout=json.loads((OUT/'source/layout.json').read_text(encoding='utf-8'))
with ZipFile(TARGET) as z:parts={n:z.read(n) for n in z.namelist()}
for page in layout:
    filename=f'ppt/slides/slide{page["index"]}.xml'
    root=E.fromstring(parts[filename]);cs=root.find('p:cSld',N)
    bg=cs.find('p:bg',N)
    if bg is not None:cs.remove(bg)
    bg=E.Element(q('p','bg'));bpr=child(bg,'p','bgPr')
    # CSS 135deg 与 DrawingML 从水平轴起算的 45deg 形成同向的左上到右下渐变。
    bpr.append(gradient(['e8f0fb','f7faff','eef3fd'],2700000));child(bpr,'a','effectLst');cs.insert(0,bg)
    byname={e['name']:e for e in page['elements']}
    for shape in root.findall('.//p:sp',N):
        nv=shape.find('p:nvSpPr/p:cNvPr',N);e=byname.get(nv.get('name')) if nv is not None else None
        if not e:continue
        sppr=shape.find('p:spPr',N)
        if e.get('gradient'):
            if e['kind']=='text':
                for run in shape.findall('.//a:rPr',N):replace_fill(run,gradient(['4d6bfe','8a5cf6'],0))
            else:replace_fill(sppr,gradient(['4d6bfe','8a5cf6'],2700000))
        if e.get('soft'):
            # 在柔化边缘之下叠加原生透明径向渐变，兼容忽略 softEdge 的预览器。
            radial=E.Element(q('a','gradFill'),{'rotWithShape':'1'})
            stops=child(radial,'a','gsLst')
            for position,alpha in [(0,55000),(50000,16000),(100000,0)]:
                stop=child(stops,'a','gs',{'pos':str(position)})
                clr=child(stop,'a','srgbClr',{'val':e['fill'].upper()})
                child(clr,'a','alpha',{'val':str(alpha)})
            radialpath=child(radial,'a','path',{'path':'circle'})
            child(radialpath,'a','fillToRect',{'l':'50000','t':'50000','r':'50000','b':'50000'})
            replace_fill(sppr,radial)
            effects=sppr.find('a:effectLst',N)
            if effects is None:effects=child(sppr,'a','effectLst')
            child(effects,'a','softEdge',{'rad':str(round(e['soft']*12700))})
    parts[filename]=xml(root)

palette='''母版设计备注 / MASTER DESIGN NOTES
交付主题：浅蓝 Glassmorphism（整册统一）；16:9，13.333 × 7.5 in。
浅色：背景 #e8f0fb → #f7faff → #eef3fd；蓝 #4d6bfe；紫 #8a5cf6（仅与蓝渐变）；标题 #1c2541；正文 #3a4763 / #5b6b8c；弱化 #6b7a99 / #8a97b5 / #98a4c0；绿 #0e9f6e；橙 #c2690a；信息蓝 #1d5fd6；粉 #c2256f，语义底色为 10% 不透明。
深色备选（本册未使用）：背景 #0b1120；标题 #e9eef8；正文 #a9b6cf；弱化 #8797b5；蓝 #6b85ff；绿 #34d399；橙 #fbbf24；信息蓝 #60a5fa；粉 #f472b6；卡片 rgba(18,24,44,0.5)，白边 14% 不透明。
卡片：白色 38% 透明，白边 1pt / 15% 透明，外圆角约 0.13in；内卡白色 50% 透明、0.09in。阴影 #465aa0 / 16% 不透明，模糊 40pt、偏移 16pt、90°。
色球：#c3d6fb / #dcd0fb / #c9e8f6；45–48% 透明；柔化边缘 65pt；位于内容底层，每页不超过两个。
字体：Noto Serif SC 900（静态 Black 字重）；Noto Sans SC 400/500/700；Consolas 数字参数。标题 44/32pt；正文 14pt，行距 1.8；卡片 12pt，行距 1.7；卡题 15pt；标签 11pt，字距约 2pt；参数 10–12pt。
所有文字和图形原生可编辑；仅第 12 页的真实界面截图使用 PNG。正文不作为整页图像。'''

# 将配色与参数同时保存在两类母版的扩展信息和首页可读演讲备注中。
custom='urn:prompt-optimizer-platform:presentation:design-notes:v1';E.register_namespace('po',custom)
for filename in ['ppt/slideMasters/slideMaster1.xml','ppt/notesMasters/notesMaster1.xml']:
    root=E.fromstring(parts[filename]);extlist=root.find('p:extLst',N)
    if extlist is None:extlist=child(root,'p','extLst')
    ext=child(extlist,'p','ext',{'uri':'{4777FA47-1B4A-47DB-91B9-FED87D1A1DA4}'})
    note=E.SubElement(ext,'{'+custom+'}masterNotes');note.text=palette
    parts[filename]=xml(root)
notes=E.fromstring(parts['ppt/notesSlides/notesSlide1.xml'])
for shape in notes.findall('.//p:sp',N):
    ph=shape.find('p:nvSpPr/p:nvPr/p:ph',N)
    if ph is not None and ph.get('type')=='body':
        tx=shape.find('p:txBody',N)
        for line in ('\n'+palette).splitlines():
            p=child(tx,'a','p');r=child(p,'a','r');child(r,'a','rPr',{'lang':'zh-CN','sz':'1200'});child(r,'a','t').text=line
parts['ppt/notesSlides/notesSlide1.xml']=xml(notes)
(OUT/'母版设计规范.md').write_text('# 母版设计规范\n\n'+palette.replace('\n','\n\n')+'\n',encoding='utf-8')

# EOT 字体嵌入采用 PresentationML 字体关系；仅嵌入本材料使用的字形。
pr=E.fromstring(parts['ppt/presentation.xml']);pr.set('embedTrueTypeFonts','1');pr.set('saveSubsetFonts','1')
emb=pr.find('p:embeddedFontLst',N)
if emb is not None:pr.remove(emb)
emb=E.Element(q('p','embeddedFontLst'))
before=pr.find('p:defaultTextStyle',N);pr.insert(list(pr).index(before) if before is not None else len(pr),emb)
rel_ns='http://schemas.openxmlformats.org/package/2006/relationships'
rels=E.fromstring(parts['ppt/_rels/presentation.xml.rels'])
font_specs=[('Noto Sans SC',[('regular','NotoSansSC-400'),('bold','NotoSansSC-700')]),('Noto Sans SC Medium',[('regular','NotoSansSC-500')]),('Noto Serif SC',[('bold','NotoSerifSC-900')])]
idx=100
for family,styles in font_specs:
    ent=child(emb,'p','embeddedFont');child(ent,'p','font',{'typeface':family,'pitchFamily':'34','charset':'-122'})
    for style,stem in styles:
        rid='rId'+str(idx);idx+=1;child(ent,'p',style,{q('r','id'):rid})
        E.SubElement(rels,'{'+rel_ns+'}Relationship',{'Id':rid,'Type':N['r']+'/font','Target':'fonts/'+stem+'.fntdata'})
        parts['ppt/fonts/'+stem+'.fntdata']=(OUT/'assets/fonts'/(stem+'.fntdata')).read_bytes()
parts['ppt/presentation.xml']=xml(pr)
E.register_namespace('',rel_ns)
parts['ppt/_rels/presentation.xml.rels']=xml(rels)
ctype='http://schemas.openxmlformats.org/package/2006/content-types';ct=E.fromstring(parts['[Content_Types].xml'])
if not any(e.get('Extension')=='fntdata' for e in ct):E.SubElement(ct,'{'+ctype+'}Default',{'Extension':'fntdata','ContentType':'application/x-fontdata'})
E.register_namespace('',ctype)
parts['[Content_Types].xml']=xml(ct)

temporary=TARGET.with_suffix('.building.pptx')
with ZipFile(temporary,'w',ZIP_DEFLATED) as z:
    for filename,body in parts.items():z.writestr(filename,body)
temporary.replace(TARGET)
print(json.dumps({'slides':len(layout),'embeddedFontParts':4,'gradients':'native DrawingML','masterNotes':True,'pptxBytes':TARGET.stat().st_size}))
