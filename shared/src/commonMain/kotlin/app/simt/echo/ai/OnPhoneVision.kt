package app.simt.echo.ai

/**
 * On-phone engine factory (plan §7.5 / W5-01): builds a [LocalVision]
 * over a pushed `.litertlm` model file, or returns null when this
 * platform/build cannot run it. Android bundles LiteRT-LM; iOS is out
 * of v1 scope and always returns null.
 */
expect fun createOnPhoneVision(modelPath: String): LocalVision?
