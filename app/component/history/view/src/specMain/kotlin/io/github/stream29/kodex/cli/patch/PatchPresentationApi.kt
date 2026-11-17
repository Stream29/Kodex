package io.github.stream29.kodex.cli.patch

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatch
import io.github.stream29.kodex.utils.applypatch.Patch

public expect fun Patch.toPendingPatchPresentation(): PatchPresentation

public expect fun StablePatch.toStablePatchPresentation(): PatchPresentation
