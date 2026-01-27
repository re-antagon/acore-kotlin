package org.antagon.acore.util

import org.bukkit.Material

object MaterialValidator {
    fun validateMaterial(materialName: String): Material {
        val material = Material.matchMaterial(materialName)

        if (material == null) throw IllegalArgumentException("Material not found")

        return material
    }

    fun validateMaterials(checkList: Collection<String>): Set<Material> {
        val validMaterials = HashSet<Material>()

        for (materialName in checkList) {
            val material = Material.matchMaterial(materialName)
            if (material != null) validMaterials.add(material)
        }

        return validMaterials
    }
}