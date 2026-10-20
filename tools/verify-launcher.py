"""Read-only headphone state: exercise the project-owned emulator Launcher and offline Tile."""
from pathlib import Path
import argparse, subprocess, re, json, uuid, xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parent.parent
VERSION=re.search(r'versionName = "([^"]+)"', (ROOT/'app/build.gradle.kts').read_text(encoding='utf-8')).group(1)
parser=argparse.ArgumentParser()
parser.add_argument('--output',type=Path,default=ROOT/'evidence'/VERSION/'launcher')
OUT=parser.parse_args().output.resolve()
if not OUT.is_relative_to(ROOT):
    raise ValueError('Launcher evidence must remain inside the project.')
SERIAL='emulator-5582'
def adb(*args):
    return subprocess.check_output(['adb','-s',SERIAL,*args],text=True,encoding='utf-8',errors='replace').strip()
def shell(*args): return adb('shell',*args)
def bounds(node): return tuple(map(int,re.findall(r'\d+',node.attrib['bounds'])))
def tap(node):
    x1,y1,x2,y2=bounds(node);shell('input','tap',str((x1+x2)//2),str((y1+y2)//2))
def capture(name):
    devicePath='/sdcard/sounddeck-widget-validation-'+uuid.uuid4().hex+'.xml'
    path=OUT/(name+'.xml')
    try:
        for attempt in range(3):
            if 'dumped to:' in shell('uiautomator','dump',devicePath):
                break
        else:
            raise RuntimeError('No fresh UI hierarchy for '+name)
        adb('pull',devicePath,str(path))
    finally:
        shell('rm','-f',devicePath)
    with (OUT/(name+'.png')).open('wb') as f:
        subprocess.run(['adb','-s',SERIAL,'exec-out','screencap','-p'],stdout=f,check=True)
    return ET.parse(path).getroot()
def find(root,id): return next(n for n in root.iter('node') if n.attrib.get('resource-id')=='dev.sounddeck:id/'+id)
def opened(root): return any(n.attrib.get('package')=='dev.sounddeck' and n.attrib.get('text')=='SoundDeck' for n in root.iter('node'))
assert 'SoundDeck_Api36' in adb('emu','avd','name').splitlines()
OUT.mkdir(parents=True,exist_ok=True)
size=shell('wm','size');density=shell('wm','density');font=shell('settings','get','system','font_scale');night=shell('cmd','uimode','night')
sizeRestore=re.search(r'Override size: (\S+)',size);densityRestore=re.search(r'Override density: (\d+)',density)
nightRestore=re.search(r'Night mode: (yes|no|auto|custom)',night)
report={}
try:
    shell('wm','density','420');shell('settings','put','system','font_scale','1.0')
    ids=['widget_title','widget_status','widget_left','widget_case','widget_right','widget_anc','widget_ambient','widget_off',*[f'widget_tick_{i}' for i in range(2,21,2)],'widget_eq_previous','widget_eq','widget_eq_next']
    for name,mode,size,scale in [('light','no','1080x2400','1.0'),('dark','yes','1080x2400','1.0'),
        ('short','no','1680x840','2.0'),('font2-light','no','1080x2400','2.0'),('font2-dark','yes','1080x2400','2.0')]:
        shell('settings','put','system','font_scale',scale)
        shell('wm','size',size);shell('cmd','uimode','night',mode);shell('input','keyevent','3')
        ui=capture(name);widget=find(ui,'widget_root');area=bounds(widget)
        children={id:find(widget,id) for id in ids}
        for id,node in children.items():
            x1,y1,x2,y2=bounds(node)
            assert area[0]<=x1<x2<=area[2] and area[1]<=y1<y2<=area[3],(name,id,area,(x1,y1,x2,y2))
        batteries=[children[id] for id in ['widget_left','widget_case','widget_right']]
        assert all(bounds(a)[2]<=bounds(b)[0] for a,b in zip(batteries,batteries[1:]))
        assert all(n.attrib.get('text')=='--%' for n in batteries)
        assert all(n.attrib.get('content-desc','').startswith(label) for n,label in zip(batteries,['左耳','充電盒','右耳']))
        assert all('✓' not in children[id].attrib.get('text','') for id in ['widget_anc','widget_ambient','widget_off'])
        assert not children['widget_eq'].attrib.get('text','').startswith('EQ')
        assert not any(n.attrib.get('resource-id') in ['dev.sounddeck:id/widget_level','dev.sounddeck:id/widget_voice'] for n in widget.iter('node'))
        ticks=[children[f'widget_tick_{i}'] for i in range(2,21,2)]
        assert all(bounds(a)[2]<=bounds(b)[0] for a,b in zip(ticks,ticks[1:]))
        report[name]={'elementsInside':len(children),'widgetBoundsPx':area,'widgetDp':[(area[2]-area[0])/2.625,(area[3]-area[1])/2.625],'batteryOrder':['左耳','充電盒','右耳']}
        if name=='light':
            tap(children['widget_title']);assert opened(capture('widget-open'))
            shell('cmd','statusbar','expand-settings');qs=capture('tile')
            parents={c:n for n in qs.iter() for c in n}
            tile=next(n for n in qs.iter('node') if n.attrib.get('content-desc','').startswith('SoundDeck 降噪'))
            while tile.attrib.get('clickable')!='true' and tile in parents: tile=parents[tile]
            tap(tile);assert opened(capture('tile-open'))
    report['offlineShortcuts']='Widget title and QS Tile opened SoundDeck; no headphone operation claimed'
    report['result']='PASS'
finally:
    shell('wm','size',sizeRestore[1] if sizeRestore else 'reset')
    shell('wm','density',densityRestore[1] if densityRestore else 'reset')
    shell('settings', 'delete' if font=='null' else 'put','system','font_scale',*([] if font=='null' else [font]))
    shell('cmd','uimode','night',nightRestore[1] if nightRestore else 'no')
    (OUT.parent/'launcher-audit.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(report,ensure_ascii=False,indent=2))
