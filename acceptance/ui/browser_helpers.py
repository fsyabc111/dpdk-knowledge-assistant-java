"""只通过browser-harness公共CDP/坐标接口操作合成UI，定位来自当前AX树。"""
import base64
import json
from pathlib import Path
import time

def install(api, out):
    out = Path(out); out.mkdir(parents=True, exist_ok=True)
    cdp, js = api['cdp'], api['js']
    api['switch_tab'](next(t for t in api['list_tabs']() if t['url'].startswith('http://127.0.0.1:15174')))
    cdp('Emulation.setDeviceMetricsOverride', width=1440, height=1000, deviceScaleFactor=1, mobile=False)
    def nodes():
        return [(n.get('role',{}).get('value'),n.get('name',{}).get('value'),n.get('backendDOMNodeId'))
                for n in cdp('Accessibility.getFullAXTree')['nodes']
                if n.get('role',{}).get('value') in ('button','textbox','heading','combobox','link','alert','checkbox')]
    def hit(name, role='button', index=0):
        candidates = [n for n in nodes() if n[0] == role and n[1] == name]
        if len(candidates) <= index: raise AssertionError('AX定位不存在: '+str((role,name,index)))
        node = candidates[index][2]
        cdp('DOM.scrollIntoViewIfNeeded',backendNodeId=node)
        q = cdp('DOM.getBoxModel',backendNodeId=node)['model']['content']
        api['click_at_xy'](sum(q[0::2])/4,sum(q[1::2])/4)
    def text(name, value, index=0):
        hit(name,'textbox',index)
        api['press_key']('a',modifiers=4)
        cdp('Input.insertText',text=value)
    def wait_text(value, timeout=15):
        end=time.monotonic()+timeout
        while time.monotonic()<end:
            body=js('document.body.innerText')
            if value in body: return body
            time.sleep(.1)
        raise AssertionError('等待UI内容超时: '+value)
    def capture(name, assertions):
        assert all(assertions.values()), assertions
        (out/(name+'.png')).write_bytes(base64.b64decode(cdp('Page.captureScreenshot',format='png')['data']))
        evidence={'case':name,'assertions':assertions,'page':api['page_info'](),'ax':nodes()}
        (out/(name+'.json')).write_text(json.dumps(evidence,ensure_ascii=False,indent=2))
        print(name,assertions)
    return nodes,hit,text,wait_text,capture
