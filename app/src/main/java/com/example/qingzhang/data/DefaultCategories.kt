package com.example.qingzhang.data

object DefaultCategories {
    val values = listOf(
        CategoryEntity(name = "餐饮", type = TransactionType.EXPENSE, color = 0xFFE67C52, sortOrder = 1),
        CategoryEntity(name = "交通", type = TransactionType.EXPENSE, color = 0xFF4C8DFF, sortOrder = 2),
        CategoryEntity(name = "购物", type = TransactionType.EXPENSE, color = 0xFFAB72D9, sortOrder = 3),
        CategoryEntity(name = "住房", type = TransactionType.EXPENSE, color = 0xFF8B7355, sortOrder = 4),
        CategoryEntity(name = "医疗", type = TransactionType.EXPENSE, color = 0xFFE45D75, sortOrder = 5),
        CategoryEntity(name = "娱乐", type = TransactionType.EXPENSE, color = 0xFFD6A72C, sortOrder = 6),
        CategoryEntity(name = "教育", type = TransactionType.EXPENSE, color = 0xFF5577CC, sortOrder = 7),
        CategoryEntity(name = "其他支出", type = TransactionType.EXPENSE, color = 0xFF7D8790, sortOrder = 8),
        CategoryEntity(name = "工资", type = TransactionType.INCOME, color = 0xFF16836B, sortOrder = 1),
        CategoryEntity(name = "奖金", type = TransactionType.INCOME, color = 0xFF2C9A68, sortOrder = 2),
        CategoryEntity(name = "理财", type = TransactionType.INCOME, color = 0xFF3C9A9A, sortOrder = 3),
        CategoryEntity(name = "红包", type = TransactionType.INCOME, color = 0xFFD45E5E, sortOrder = 4),
        CategoryEntity(name = "其他收入", type = TransactionType.INCOME, color = 0xFF668877, sortOrder = 5),
    )
}
