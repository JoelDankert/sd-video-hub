#!/usr/bin/env node
'use strict';

process.env.TZ='UTC';
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const source=fs.readFileSync(require('node:path').join(__dirname,'index.html'),'utf8');
const helpers=source.match(/const fmt=.*?;\s*const dur=.*?;\s*const recordedDate=.*?;\s*const videoDetails=.*?;\s*const galleryKey=.*?;/s);
assert.ok(helpers,'actual gallery helper block is present');
const context={};
vm.runInNewContext(`${helpers[0]}this.api={recordedDate,videoDetails,galleryKey}`,context);
const {recordedDate,videoDetails,galleryKey}=context.api;
const base={id:1,name:'hdz_0000.mp4',output:'/library/hdz_0000.mp4',duration:125,flight_duration:65,output_size:2e6,recorded_source:'embedded',recorded_at:Date.UTC(2024,0,2,12)/1000};

assert.equal(recordedDate(base),'02.01.2024');
assert.equal(videoDetails(base),'1:05 / 2:05 · 02.01.2024 · 2.0 MB · MP4');
assert.equal(recordedDate({...base,name:'',output:'/library/HDZ_fallback.mp4'}),'02.01.2024');
for(const row of [
  {...base,recorded_source:'filesystem'},
  {...base,name:'camera.mp4',output:'/library/camera.mp4'},
  {...base,recorded_at:null},{...base,recorded_at:''},{...base,recorded_at:'bad'},
  {...base,recorded_at:0},{...base,recorded_at:-1},{...base,recorded_at:Infinity},{...base,recorded_at:9e15}
]) assert.equal(recordedDate(row),'');
assert.equal(videoDetails({...base,recorded_source:'filesystem'}),'1:05 / 2:05 · 2.0 MB · MP4');
assert.notEqual(galleryKey([base]),galleryKey([{...base,recorded_at:base.recorded_at+1}]));
assert.notEqual(galleryKey([base]),galleryKey([{...base,recorded_source:'filesystem'}]));
const legacy={...base,skip_intervals:undefined};
assert.equal(typeof galleryKey([legacy]),'string');
assert.notEqual(galleryKey([legacy]),galleryKey([legacy],{'hdz_0000.mp4':[[1,2]]}));
console.log('recorded-date regression: all assertions passed');
