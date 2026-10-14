-- Prevent duplicate package codes regardless of letter case
CREATE UNIQUE INDEX IF NOT EXISTS uq_membership_packages_code_lower
ON public.membership_packages (LOWER(code));

-- Prevent duplicate package names regardless of letter case
CREATE UNIQUE INDEX IF NOT EXISTS uq_membership_packages_name_lower
ON public.membership_packages (LOWER(name));