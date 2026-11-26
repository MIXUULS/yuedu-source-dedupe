package com.mina.yuedu.network;

import com.mina.yuedu.model.InvalidSource;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 判断一次网络响应该怎么归类：书源数组 / 间接地址 / 无可提取内容。
 *
 * 从 MainActivity.startFetch 的 onItem 回调抽出来的原因：
 * 这段是"抓回来的东西到底是什么"的唯一决策点，分支多且都在匿名内部类里，
 * 既没法单测也没法复用。出错的表现是"某些 YCK 页面抓不到书源"或
 * "错误信息被吞掉"，用户只会看到成功数少了几条。
 *
 * 处理顺序（不可调换）：
 * <pre>
 *   1. 已解析出书源    -> 直接收下
 *   2. 正文里有间接地址 -> 加入待抓取队列（YCK 常见的跳转层）
 *   3. 扫描出 JSON 地址 -> 加入待抓取队列
 *   4. 都没有          -> 记为无效源，带上具体原因
 * </pre>
 */
public final class FetchResponseClassifier {

  /** 分类结果。 */
  public static final class Result {
    /** 直接可用的书源解析结果，null 表示没有。 */
    public final SourceParser.ParseResult parsed;
    /** 需要继续抓取的地址（间接地址 + 扫描出的 JSON 地址），保序去重。 */
    public final List<String> discovered;
    /** 归类为无效源时的原因，null 表示不是无效源。 */
    public final InvalidSource invalid;

    private Result(SourceParser.ParseResult parsed, List<String> discovered, InvalidSource invalid) {
      this.parsed = parsed;
      this.discovered = discovered;
      this.invalid = invalid;
    }

    /** 是否直接拿到了书源。 */
    public boolean hasRecords() {
      return parsed != null && !parsed.getRecords().isEmpty();
    }

    /** 是否还有可继续抓取的地址。 */
    public boolean hasMore() {
      return !discovered.isEmpty();
    }

    /** 是否是无效响应。 */
    public boolean isInvalid() {
      return invalid != null;
    }
  }

  private FetchResponseClassifier() {}

  /**
   * @param url    本次请求的地址（用于错误信息）
   * @param body   响应正文（用于提取间接地址）
   * @param parsed FetchManager 已解析好的结果
   */
  public static Result classify(String url, String body, SourceParser.ParseResult parsed) {
    // 1. 已经有书源，直接用
    if (parsed != null && !parsed.getRecords().isEmpty()) {
      return new Result(parsed, new ArrayList<String>(), null);
    }

    // 2. 正文里的间接地址（单跳转页、YCK 详情页常见）
    List<String> found = new ArrayList<>();
    if (body != null && !body.isEmpty()) {
      String indirect = SourceParser.extractIndirectUrl(body);
      if (indirect != null && !indirect.isEmpty()) found.add(indirect);
      found.addAll(SourceParser.discoverYckJsonUrls(body, url));
    }

    // 保序去重：同一个地址可能既被 indirect 命中又被 JSON 扫描命中
    LinkedHashSet<String> unique = new LinkedHashSet<>(found);
    List<String> discovered = new ArrayList<>(unique);

    if (!discovered.isEmpty()) {
      return new Result(null, discovered, null);
    }

    // 3. 什么都没提取到：优先用解析器给出的具体原因，否则报"非书源数组"
    InvalidSource invalid;
    if (parsed != null && !parsed.getInvalid().isEmpty()) {
      invalid = parsed.getInvalid().get(0);
    } else {
      // 允许 body 为 null（网络层只回传了状态）
      invalid = new InvalidSource(InvalidSource.Kind.NOT_JSON_ARRAY, url + " · 响应非书源数组");
    }
    return new Result(null, discovered, invalid);
  }
}