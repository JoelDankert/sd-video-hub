#!/usr/bin/env node
'use strict';

const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {chromium}=require('playwright');

const source=fs.readFileSync(path.join(__dirname,'index.html'),'utf8');
const html=source.replace('__SDH_CUTLIST__',JSON.stringify('cutlist.json'));
const state={files:[{id:1,name:'hdz_test.mp4',output:'/library/hdz_test.mp4',status:'done',duration:60,flight_duration:30,output_size:1000,video_url:'/media/test.mp4',thumb_url:'',skip_intervals:[]}],sessions:[],devices:[],settings:{archive:{sort_direction:'asc'},playback:{skip_nonflight:false,crop_4_3:true}}};
const ids=['skip-toggle','crop-toggle','hide-short-toggle'];

async function serve(page){
  await page.route('**/*',route=>{
    const pathname=new URL(route.request().url()).pathname;
    if(pathname==='/')return route.fulfill({contentType:'text/html',body:html});
    if(pathname==='/api/state')return route.fulfill({json:state});
    return route.abort();
  });
}

async function states(page){
  return page.locator('.stream-option').evaluateAll(labels=>labels.map(label=>{
    const input=label.querySelector('input'),style=getComputedStyle(label);
    return {id:input.id,checked:input.checked,selected:label.classList.contains('is-checked'),background:style.backgroundColor,color:style.color};
  }));
}

function assertDeterministic(rows,message){
  for(const row of rows){
    assert.equal(row.selected,row.checked,`${message}: ${row.id} class follows checked property`);
    if(row.checked){
      assert.equal(row.background,'rgb(214, 168, 73)',`${message}: ${row.id} has uniform gold fill`);
      assert.equal(row.color,'rgb(16, 17, 18)',`${message}: ${row.id} has readable dark text`);
    }else assert.equal(row.background,'rgb(25, 26, 28)',`${message}: ${row.id} remains dark`);
  }
}

async function assertLayout(page,width){
  await page.setViewportSize({width,height:900});
  await page.locator('html').evaluate(node=>node.style.fontSize='150%');
  const result=await page.evaluate(()=>{
    const controls=document.querySelector('.stream-controls'),labels=[...document.querySelectorAll('.stream-option')];
    const cr=controls.getBoundingClientRect(),rects=labels.map(label=>{
      const r=label.getBoundingClientRect(),input=label.querySelector('input').getBoundingClientRect(),text=label.querySelector('.stream-option-text').getBoundingClientRect();
      return {left:r.left,right:r.right,top:r.top,bottom:r.bottom,height:r.height,inputLeft:input.left,textLeft:text.left};
    });
    return {scrollWidth:document.documentElement.scrollWidth,clientWidth:document.documentElement.clientWidth,left:cr.left,right:cr.right,rects};
  });
  assert.equal(result.scrollWidth,result.clientWidth,`${width}px has no page horizontal overflow`);
  for(const rect of result.rects){
    assert.ok(rect.left>=result.left-.5&&rect.right<=result.right+.5,`${width}px option stays inside row`);
    assert.ok(rect.height>=44,`${width}px option retains touch height`);
    assert.ok(rect.textLeft>rect.inputLeft,`${width}px text uses a stable column after checkbox`);
  }
  for(let i=0;i<result.rects.length;i++)for(let j=i+1;j<result.rects.length;j++){
    const a=result.rects[i],b=result.rects[j],overlap=a.left<b.right&&b.left<a.right&&a.top<b.bottom&&b.top<a.bottom;
    assert.equal(overlap,false,`${width}px controls do not overlap`);
  }
  const firstLeftByRow=new Map();
  for(const rect of result.rects){const row=Math.round(rect.top);if(!firstLeftByRow.has(row))firstLeftByRow.set(row,rect.left);else assert.ok(Math.abs(firstLeftByRow.get(row)-rect.left)<1||width>=768,`${width}px wrapped controls remain left aligned`)}
}

(async()=>{
  const browser=await chromium.launch({headless:true});
  try{
    const context=await browser.newContext({viewport:{width:1280,height:900}}),page=await context.newPage();page.setDefaultTimeout(5000);await serve(page);
    await page.goto('http://sdh.test/');await page.waitForSelector('.video');
    assert.match(await page.locator('label[for="hide-short-toggle"], .stream-option:has(#hide-short-toggle)').textContent(),/unter 30 s/);
    assertDeterministic(await states(page),'server defaults');
    assert.deepEqual((await states(page)).map(x=>x.checked),[false,true,false],'server defaults apply before visual sync');
    for(const id of ids)await page.locator('#'+id).check();
    assertDeterministic(await states(page),'all selected after click');
    await page.screenshot({path:'/tmp/sdh-controls-desktop-1280-all-selected.png',fullPage:true});
    await page.reload();await page.waitForSelector('.video');
    assertDeterministic(await states(page),'localStorage reload');
    assert.deepEqual((await states(page)).map(x=>x.checked),[true,true,true],'all preferences survive reload');
    await page.locator('#skip-toggle').uncheck();await page.locator('#hide-short-toggle').uncheck();
    assertDeterministic(await states(page),'mixed after click');
    await page.screenshot({path:'/tmp/sdh-controls-desktop-1280-mixed-after-reload.png',fullPage:true});
    const crop=page.locator('#crop-toggle');await page.evaluate(()=>document.activeElement?.blur());for(let i=0;i<12&&!await crop.evaluate(node=>node===document.activeElement);i++)await page.keyboard.press('Tab');assert.equal(await crop.evaluate(node=>node.matches(':focus-visible')),true,'keyboard focus is visibly targetable');await page.keyboard.press('Space');
    assert.equal(await crop.isChecked(),false,'Space toggles native checkbox');assertDeterministic(await states(page),'keyboard toggle');
    await page.evaluate(()=>{const input=document.querySelector('#hide-short-toggle');input.checked=true;input.dispatchEvent(new Event('change',{bubbles:true}))});
    assertDeterministic(await states(page),'native restoration event');
    await page.evaluate(()=>{const input=document.querySelector('#skip-toggle');input.checked=true;window.dispatchEvent(new PageTransitionEvent('pageshow',{persisted:true}))});
    assertDeterministic(await states(page),'bfcache pageshow');
    for(const width of [320,360,390,768,1280])await assertLayout(page,width);
    await page.setViewportSize({width:320,height:900});await page.screenshot({path:'/tmp/sdh-controls-mobile-320-mixed.png',fullPage:true});
    await page.setViewportSize({width:390,height:900});for(const id of ids)await page.locator('#'+id).check();await page.screenshot({path:'/tmp/sdh-controls-mobile-390-all-selected.png',fullPage:true});
    await context.close();

    const broken=await browser.newContext({viewport:{width:320,height:900}}),brokenPage=await broken.newPage();
    await brokenPage.addInitScript(()=>Object.defineProperty(window,'localStorage',{configurable:true,get(){throw Error('unavailable')}}));
    await serve(brokenPage);await brokenPage.goto('http://broken.test/');await brokenPage.waitForSelector('.video');
    for(const id of ids)await brokenPage.locator('#'+id).check();assertDeterministic(await states(brokenPage),'unavailable storage');
    await broken.close();
    console.log('CONTROLS_REGRESSION_OK');
  }finally{await browser.close()}
})().catch(error=>{console.error(error);process.exitCode=1});
