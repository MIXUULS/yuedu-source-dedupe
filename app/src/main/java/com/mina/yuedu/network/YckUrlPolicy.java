package com.mina.yuedu.network;
import java.net.*;
import java.util.*;
public final class YckUrlPolicy {
  /** 允许从第三方域加载的静态资源类型（图片/字体/样式/脚本）。json 不在此列，防书源数据外泄。 */
  private static final Set<String> STATIC_EXT = new HashSet<>(Arrays.asList(
      "png","jpg","jpeg","gif","webp","bmp","svg","ico","avif",
      "woff","woff2","ttf","otf","eot",
      "css","js"));
  private YckUrlPolicy(){}
  public static boolean allowed(String raw){
    try{ String h=new URL(raw).getHost().toLowerCase(Locale.ROOT); return hostOk(h);}catch(Exception e){return false;}
  }
  /** YCK 域本身，或第三方域的常见静态资源（图片/字体/css/js），允许加载；其余（含 json）拦截。 */
  public static boolean staticResource(String raw){
    try{
      URL u = new URL(raw);
      if (allowed(raw)) return true;
      String path = u.getPath();
      int dot = path.lastIndexOf('.');
      if (dot < 0 || dot == path.length() - 1) return false;
      return STATIC_EXT.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }catch(Exception e){ return false; }
  }
  private static boolean hostOk(String h){
    return eqOrSub(h,"yckceo.vip")||eqOrSub(h,"yckceo.com")||eqOrSub(h,"yck2026.fun")||eqOrSub(h,"yck2026.top");
  }
  private static boolean eqOrSub(String h,String root){ return h.equals(root)||h.endsWith("."+root); }
  public static boolean safeResource(String raw){ return allowed(raw); }
  public static boolean collectable(String raw){
    try{ URL u=new URL(raw); String p=u.getPath();
      return allowed(raw)&&(p.toLowerCase(Locale.ROOT).endsWith(".json")||p.matches("^/d/[^/?#]+$"));
    }catch(Exception e){return false;}
  }
  public static boolean json(String raw){
    try{ return allowed(raw)&&new URL(raw).getPath().toLowerCase(Locale.ROOT).endsWith(".json"); }catch(Exception e){return false;}
  }
}
