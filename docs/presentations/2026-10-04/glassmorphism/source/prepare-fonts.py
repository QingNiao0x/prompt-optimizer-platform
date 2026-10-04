"""从系统中已安装的 OFL 字体制作本材料使用的静态字重子集，不改系统字体。"""
from pathlib import Path
import sys, json, struct
ROOT = Path(__file__).resolve().parents[5]
OUT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tmp/glass-promo-20261004/python-deps'))
from fontTools.ttLib import TTFont
from fontTools import subset
from fontTools.varLib.instancer import instantiateVariableFont

def name(font, number, fallback=''):
    found = font['name'].getDebugName(number)
    return found or fallback

def eot(ttf, font):
    """EOT 1.0 非压缩封装；不改变原字体嵌入许可位。"""
    os2=font['OS/2'];p=os2.panose
    panose=bytes(getattr(p,k) for k in ['bFamilyType','bSerifStyle','bWeight','bProportion','bContrast','bStrokeVariation','bArmStyle','bLetterForm','bMidline','bXHeight'])
    head=struct.pack('<IIII',0,len(ttf),0x00010000,0)+panose+bytes([1,int(bool(os2.fsSelection & 1))])
    head+=struct.pack('<IHH',os2.usWeightClass,os2.fsType,0x504c)
    head+=struct.pack('<IIIIIII',*(getattr(os2,'ulUnicodeRange'+str(i)) for i in range(1,5)),getattr(os2,'ulCodePageRange1',0),getattr(os2,'ulCodePageRange2',0),font['head'].checkSumAdjustment)
    head+=struct.pack('<IIII',0,0,0,0)
    for idx in [1,2,5,4]:
        value=name(font,idx,'Regular').encode('utf-16le')
        head+=struct.pack('<HH',0,len(value))+value
    result=head+ttf
    return struct.pack('<I',len(result))+result[4:]

fontdir=OUT/'assets/fonts';fontdir.mkdir(exist_ok=True,parents=True)
text=''.join(p.read_text(encoding='utf-8') for p in (OUT/'source').iterdir() if p.suffix in ['.cjs','.js','.css','.json','.txt'])
text+=''.join(chr(i) for i in range(32,127))+'←→×©，。；：！？？、（）【】·–—“”‘’'
characters=sorted(set(map(ord,text)))
manifest=[]
for family,source,weights in [('NotoSansSC','NotoSansSC-VF.ttf',[400,500,700]),('NotoSerifSC','NotoSerifSC-VF.ttf',[900])]:
    for weight in weights:
        font=TTFont('C:/Windows/Fonts/'+source)
        if font['OS/2'].fsType:raise ValueError('Unexpected embedding restriction')
        options=subset.Options();options.name_IDs=['*'];options.name_legacy=True;options.name_languages=['*'];options.notdef_glyph=True;options.notdef_outline=True;options.recommended_glyphs=True
        sub=subset.Subsetter(options=options);sub.populate(unicodes=characters);sub.subset(font)
        font=instantiateVariableFont(font,{'wght':weight},inplace=True)
        font['OS/2'].usWeightClass=weight
        # PowerPoint 的粗体槽位用于固定的 700/900 字重；标题视觉字形仍源自 Noto Serif SC 900。
        bold=weight>=700
        font['OS/2'].fsSelection=(font['OS/2'].fsSelection & ~0x61)|(0x20 if bold else 0x40)
        font['head'].macStyle=(font['head'].macStyle & ~3)|(1 if bold else 0)
        for record in font['name'].names:
            if record.nameID in [2,17]:
                record.string=('Bold' if bold else 'Regular').encode(record.getEncoding())
            family_name='Noto Sans SC Medium' if weight==500 else ('Noto Serif SC' if family=='NotoSerifSC' else 'Noto Sans SC')
            if record.nameID in [1,16]:
                    record.string=family_name.encode(record.getEncoding())
            face='Black' if weight==900 else 'Bold' if weight==700 else 'Regular'
            if record.nameID==4:
                record.string=(family_name+' '+face).encode(record.getEncoding())
            if record.nameID==6:
                record.string=(family_name.replace(' ','')+'-'+face).encode(record.getEncoding())
        stem=f'{family}-{weight}'
        target=fontdir/(stem+'.ttf');font.save(target)
        raw=target.read_bytes();reread=TTFont(target)
        (fontdir/(stem+'.fntdata')).write_bytes(eot(raw,reread))
        font.flavor='woff2';font.save(fontdir/(stem+'.woff2'))
        manifest.append({'file':stem,'family':name(font,1),'weight':weight,'glyphs':len(font.getGlyphOrder()),'copyright':name(font,0),'license':name(font,13),'licenseUrl':name(font,14),'source':source})
        print(stem, 'glyphs', len(font.getGlyphOrder()), 'bytes',target.stat().st_size,flush=True)
(fontdir/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
