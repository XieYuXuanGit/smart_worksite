-- =============================================================
-- V9: Simplify permission system
--
-- Before: 4 platform roles (PLATFORM_ADMIN, PROJECT_ADMIN, BUSINESS_USER, VIEWER)
--         + 12 fine-grained permissions + project_member.project_role
--         Two overlapping permission hierarchies causing confusion.
--
-- After:  2 platform roles (PLATFORM_ADMIN, USER)
--         + project_member.project_role (PROJECT_ADMIN, MEMBER, VIEWER)
--         Platform roles control platform-level access.
--         Project roles control project-level access.
-- =============================================================

-- 1. Add default USER role
INSERT IGNORE INTO role (role_code, role_name, description, status, created_by, updated_by)
VALUES ('USER', '普通用户', '系统默认用户角色，可登录并参与项目', 'ENABLED', 1, 1);

-- 2. Reassign users who had PROJECT_ADMIN/BUSINESS_USER/VIEWER platform roles to USER
INSERT IGNORE INTO user_role (user_id, role_id, created_by, updated_by)
SELECT DISTINCT ur.user_id,
       (SELECT id FROM role WHERE role_code = 'USER' AND deleted = 0 LIMIT 1),
       1, 1
FROM user_role ur
JOIN role r ON r.id = ur.role_id AND r.role_code IN ('PROJECT_ADMIN', 'BUSINESS_USER', 'VIEWER')
WHERE ur.deleted = 0
  AND ur.user_id NOT IN (
    SELECT ur2.user_id FROM user_role ur2
    JOIN role r2 ON r2.id = ur2.role_id AND r2.role_code = 'PLATFORM_ADMIN'
    WHERE ur2.deleted = 0
  );

-- 3. Hard-delete user_role assignments pointing to the 3 removed platform roles
--    (hard delete to avoid unique key conflicts on (user_id, role_id, deleted))
DELETE FROM user_role
WHERE role_id IN (SELECT id FROM role WHERE role_code IN ('PROJECT_ADMIN', 'BUSINESS_USER', 'VIEWER'));

-- 4. Soft-delete the 3 redundant platform roles
UPDATE role SET deleted = 1, updated_at = NOW()
WHERE role_code IN ('PROJECT_ADMIN', 'BUSINESS_USER', 'VIEWER') AND deleted = 0;

-- 5. Clean up permission system (no longer used for authorization)
UPDATE role_permission SET deleted = 1, updated_at = NOW() WHERE deleted = 0;
UPDATE permission SET deleted = 1, updated_at = NOW() WHERE deleted = 0;

-- 6. Rename BUSINESS_USER to MEMBER in project_member table
UPDATE project_member SET project_role = 'MEMBER', updated_at = NOW()
WHERE project_role = 'BUSINESS_USER' AND deleted = 0;
