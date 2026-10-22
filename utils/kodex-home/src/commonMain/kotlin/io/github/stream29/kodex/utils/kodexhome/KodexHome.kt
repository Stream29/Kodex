package io.github.stream29.kodex.utils.kodexhome

import io.github.stream29.kodex.utils.osenvironment.requireUserHomeDirectory
import kotlinx.io.files.Path

public actual val KodexHome: Path = Path(requireUserHomeDirectory(), ".kodex")
