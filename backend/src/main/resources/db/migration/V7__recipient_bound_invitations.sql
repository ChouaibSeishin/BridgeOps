CREATE TABLE invitations (
 id uuid PRIMARY KEY,organization_id uuid NOT NULL REFERENCES organizations(id),normalized_email varchar(254) NOT NULL,
 token_hash varchar(64) NOT NULL UNIQUE CHECK(token_hash ~ '^[a-f0-9]{64}$'),
 created_by_membership_id uuid NOT NULL,created_at timestamptz NOT NULL,expires_at timestamptz NOT NULL CHECK(expires_at>created_at),accepted_at timestamptz,revoked_at timestamptz,
 CHECK(accepted_at IS NULL OR revoked_at IS NULL),UNIQUE(organization_id,id),
 FOREIGN KEY(organization_id,created_by_membership_id) REFERENCES organization_memberships(organization_id,id),
 CHECK(normalized_email=lower(btrim(normalized_email) COLLATE "C")),CHECK(normalized_email ~ '^[\x21-\x7E]+$')
);
CREATE UNIQUE INDEX one_pending_invitation ON invitations(organization_id,normalized_email) WHERE accepted_at IS NULL AND revoked_at IS NULL;
CREATE INDEX invitations_expiry ON invitations(expires_at);
CREATE TABLE invitation_roles (
 organization_id uuid NOT NULL,invitation_id uuid NOT NULL,role_id uuid NOT NULL,
 PRIMARY KEY(organization_id,invitation_id,role_id),
 FOREIGN KEY(organization_id,invitation_id) REFERENCES invitations(organization_id,id),
 FOREIGN KEY(organization_id,role_id) REFERENCES roles(organization_id,id)
);
-- Narrow enrollment capability: runtime still cannot INSERT users or assign account grants.
-- All relation names are qualified, search_path is trusted, PUBLIC execution is revoked in this migration transaction.
CREATE FUNCTION public.enroll_invitation(p_token text,p_email text,p_hash text,p_name text)
RETURNS TABLE(new_user_id uuid,new_membership_id uuid,tenant_id uuid)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $enroll$
DECLARE v_inv public.invitations%ROWTYPE;v_issuer uuid;v_user uuid;v_member uuid;v_now timestamptz;
BEGIN
 IF p_token IS NULL OR p_token !~ '^[A-Za-z0-9_-]{43}$' OR p_email IS NULL OR p_hash IS NULL
    OR p_hash !~ '^\$2[aby]\$12\$[./A-Za-z0-9]{53}$' OR p_name IS NULL OR length(btrim(p_name)) NOT BETWEEN 1 AND 120 OR p_name ~ '[[:cntrl:]]' THEN
  RAISE EXCEPTION USING ERRCODE='22023',MESSAGE='Invitation was not accepted.';
 END IF;
 SELECT i.* INTO v_inv FROM public.invitations i WHERE i.token_hash=encode(sha256(convert_to(p_token,'UTF8')),'hex');
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='22023',MESSAGE='Invitation was not accepted.'; END IF;
 PERFORM 1 FROM public.organizations o WHERE o.id=v_inv.organization_id AND o.status='ACTIVE' FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='22023',MESSAGE='Invitation was not accepted.'; END IF;
 SELECT i.* INTO v_inv FROM public.invitations i WHERE i.id=v_inv.id AND i.organization_id=v_inv.organization_id FOR UPDATE;
 IF v_inv.accepted_at IS NOT NULL OR v_inv.revoked_at IS NOT NULL OR v_inv.expires_at<=clock_timestamp() OR p_email<>v_inv.normalized_email
    OR EXISTS(SELECT 1 FROM public.users u WHERE u.normalized_email=p_email) THEN
  RAISE EXCEPTION USING ERRCODE='22023',MESSAGE='Invitation was not accepted.';
 END IF;
 SELECT m.user_id INTO v_issuer FROM public.organization_memberships m JOIN public.users u ON u.id=m.user_id
  WHERE m.organization_id=v_inv.organization_id AND m.id=v_inv.created_by_membership_id AND m.status='ACTIVE' AND u.account_status='ACTIVE';
 IF NOT FOUND OR NOT EXISTS(SELECT 1 FROM public.invitation_roles r WHERE r.organization_id=v_inv.organization_id AND r.invitation_id=v_inv.id) THEN
  RAISE EXCEPTION USING ERRCODE='22023',MESSAGE='Invitation was not accepted.';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM public.membership_roles a JOIN public.role_permissions p ON p.organization_id=a.organization_id AND p.role_id=a.role_id
   WHERE a.organization_id=v_inv.organization_id AND a.membership_id=v_inv.created_by_membership_id AND p.permission_code='membership.invite')
 OR EXISTS(SELECT 1 FROM public.invitation_roles a JOIN public.roles r ON r.organization_id=a.organization_id AND r.id=a.role_id
   WHERE a.organization_id=v_inv.organization_id AND a.invitation_id=v_inv.id AND r.name IN ('OWNER','ADMIN'))
   AND NOT EXISTS(SELECT 1 FROM public.membership_roles a JOIN public.role_permissions p ON p.organization_id=a.organization_id AND p.role_id=a.role_id
     WHERE a.organization_id=v_inv.organization_id AND a.membership_id=v_inv.created_by_membership_id AND p.permission_code='membership.grant_privileged')
 OR EXISTS(SELECT 1 FROM public.invitation_roles a JOIN public.role_permissions wanted ON wanted.organization_id=a.organization_id AND wanted.role_id=a.role_id
   WHERE a.organization_id=v_inv.organization_id AND a.invitation_id=v_inv.id AND NOT EXISTS(
     SELECT 1 FROM public.membership_roles actual JOIN public.role_permissions p ON p.organization_id=actual.organization_id AND p.role_id=actual.role_id
      WHERE actual.organization_id=v_inv.organization_id AND actual.membership_id=v_inv.created_by_membership_id AND p.permission_code=wanted.permission_code)) THEN
  RAISE EXCEPTION USING ERRCODE='22023',MESSAGE='Invitation was not accepted.';
 END IF;
 v_user=gen_random_uuid();v_member=gen_random_uuid();v_now=clock_timestamp();
 INSERT INTO public.users(id,email,normalized_email,password_hash,display_name,account_status,created_at,email_verified_at) VALUES(v_user,p_email,p_email,p_hash,btrim(p_name),'ACTIVE',v_now,v_now);
 INSERT INTO public.organization_memberships(id,organization_id,user_id,status,joined_at) VALUES(v_member,v_inv.organization_id,v_user,'ACTIVE',v_now);
 INSERT INTO public.membership_roles(organization_id,membership_id,role_id) SELECT r.organization_id,v_member,r.role_id FROM public.invitation_roles r WHERE r.organization_id=v_inv.organization_id AND r.invitation_id=v_inv.id;
 UPDATE public.invitations i SET accepted_at=clock_timestamp() WHERE i.id=v_inv.id AND i.organization_id=v_inv.organization_id AND i.accepted_at IS NULL AND i.revoked_at IS NULL AND i.expires_at>clock_timestamp();
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='22023',MESSAGE='Invitation was not accepted.'; END IF;
 INSERT INTO public.identity_security_events(id,user_id,event_type,occurred_at) VALUES(gen_random_uuid(),v_user,'INVITATION_ACCOUNT_CREATED',v_now);
 INSERT INTO public.organization_audit_events(id,organization_id,actor_id,action,target_id,occurred_at) VALUES(gen_random_uuid(),v_inv.organization_id,v_user,'INVITATION_ACCEPTED',v_inv.id,v_now);
 RETURN QUERY SELECT v_user,v_member,v_inv.organization_id;
END;
$enroll$;
REVOKE ALL ON FUNCTION public.enroll_invitation(text,text,text,text) FROM PUBLIC;
DO $$ BEGIN
 IF EXISTS(SELECT FROM pg_roles WHERE rolname='bridgeops_app') THEN
  GRANT SELECT,INSERT ON invitations,invitation_roles TO bridgeops_app;
  GRANT UPDATE(accepted_at,revoked_at) ON invitations TO bridgeops_app;
  REVOKE INSERT ON users FROM bridgeops_app;
  GRANT EXECUTE ON FUNCTION public.enroll_invitation(text,text,text,text) TO bridgeops_app;
 END IF;
END $$;
