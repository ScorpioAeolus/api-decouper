package com.api.decouper.api;
public interface TaskCodec<P,R> {
 P decodeRequest(String payload,int version);
 String encodeResult(R result);
 R decodeResult(String payload,int version);
 default int resultVersion(){return 1;}
 static TaskCodec<String,String> strings(){return new TaskCodec<String,String>() {
  public String decodeRequest(String p,int v){if(v!=1)throw new IllegalArgumentException("unsupported input version");return p;}
  public String encodeResult(String r){return r;}
  public String decodeResult(String p,int v){if(v!=1)throw new IllegalArgumentException("unsupported result version");return p;}
 };}
}
