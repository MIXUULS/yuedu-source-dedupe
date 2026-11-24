package com.mina.yuedu.check;

import com.mina.yuedu.core.RuleCompleteness;
import com.mina.yuedu.core.SourceCleaner;
import com.mina.yuedu.model.SourceRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * 校验前的目标准备：按设置过滤掉不该校验的书源，并统计规则不完整的情况。
 *
 * 从 MainActivity.runCheck 抽出来的原因：这段决定"到底校验哪些源"，
 * 过滤顺序（先删验证码源，再清理登录源）会直接影响校验结果的数量与构成，
 * 但原来埋在 79 行的流程方法里，既无法单测也无法复用。
 */
public final class CheckTargetPreparer {

  /** 准备结果。 */
  public static final class Prepared {
    /** 实际要校验的书源（已按设置过滤）。 */
    public final List<SourceRecord> targets;
    /** 缺少部分规则的书源数量（用于提示"结果会标明失败步骤"）。 */
    public final int incompleteRules;
    /** 因缺少验证码规则被剔除的数量。 */
    public final int removedCaptcha;
    /** 因需要登录被剔除的数量。 */
    public final int removedLogin;

    Prepared(List<SourceRecord> targets, int incompleteRules, int removedCaptcha, int removedLogin) {
      this.targets = targets;
      this.incompleteRules = incompleteRules;
      this.removedCaptcha = removedCaptcha;
      this.removedLogin = removedLogin;
    }
  }

  private CheckTargetPreparer() {}

  /**
   * 按设置准备校验目标。
   *
   * 顺序很重要：先删验证码源，再清理登录源。
   * 反过来会让"同时需要登录+带验证码"的源在登录清理阶段被当作可校验源保留下来。
   *
   * @param sources 候选书源，可为 null
   * @param settings 校验设置，可为 null（视作不做任何清理）
   */
  public static Prepared prepare(List<SourceRecord> sources, CheckSourceSettings settings) {
    List<SourceRecord> input = sources == null ? new ArrayList<SourceRecord>() : new ArrayList<>(sources);
    int original = input.size();

    List<SourceRecord> afterCaptcha = input;
    if (settings != null && settings.deleteCaptcha) {
      afterCaptcha = SourceCleaner.cleanCaptcha(input);
    }
    int removedCaptcha = original - afterCaptcha.size();

    List<SourceRecord> finalTargets = afterCaptcha;
    if (settings != null && settings.cleanLogin) {
      finalTargets = SourceCleaner.cleanLogin(afterCaptcha);
    }
    int removedLogin = afterCaptcha.size() - finalTargets.size();

    int incomplete = 0;
    for (SourceRecord s : finalTargets) {
      if (!RuleCompleteness.isComplete(s)) incomplete++;
    }
    return new Prepared(finalTargets, incomplete, removedCaptcha, removedLogin);
  }
}